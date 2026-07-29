package dev.ndcshelf.app.data.repository

import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.ReadingSessionEntity
import dev.ndcshelf.app.data.local.ReadingSessionRow
import dev.ndcshelf.app.data.sync.syncDelete
import dev.ndcshelf.app.data.sync.toSyncUpsert
import dev.ndcshelf.app.domain.model.PartialDate
import dev.ndcshelf.app.domain.model.ReadingSession
import dev.ndcshelf.app.domain.model.ReadingSessionDraft
import dev.ndcshelf.app.domain.model.ReadingSessionStatus
import dev.ndcshelf.app.domain.model.ReadingSessionValidationResult
import dev.ndcshelf.app.domain.model.ReadingSessionValidator
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.ValidatedReadingSession
import dev.ndcshelf.app.domain.repository.AddReadingSessionResult
import dev.ndcshelf.app.domain.repository.DeleteReadingSessionResult
import dev.ndcshelf.app.domain.repository.ReadingHistoryRepository
import dev.ndcshelf.app.domain.repository.RestoreReadingSessionResult
import dev.ndcshelf.app.domain.repository.UpdateReadingSessionResult
import dev.ndcshelf.app.domain.sync.SyncMutation
import dev.ndcshelf.app.domain.sync.SyncMutationJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomReadingHistoryRepository(
    private val database: AppDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncJournal: SyncMutationJournal = SyncMutationJournal.Disabled,
) : ReadingHistoryRepository {
    private val dao = database.readingSessionDao()
    private val validator = ReadingSessionValidator()

    override fun observeSessionsForEdition(editionId: String): Flow<List<ReadingSession>> =
        dao.observeSessionsForEdition(editionId).map { rows ->
            rows.map(ReadingSessionRow::toDomain)
        }

    override suspend fun addSession(
        copyId: String,
        draft: ReadingSessionDraft,
    ): AddReadingSessionResult {
        val validated =
            when (val result = validator.validate(draft)) {
                is ReadingSessionValidationResult.Invalid -> {
                    return AddReadingSessionResult.Invalid(result.errors)
                }

                is ReadingSessionValidationResult.Valid -> {
                    result.session
                }
            }
        return try {
            database.withTransaction {
                val copy =
                    database.libraryDao().findCopyById(copyId)
                        ?: return@withTransaction AddReadingSessionResult.CopyNotFound
                val existing = dao.findByCopyId(copyId)
                if (validated.status.isActive() && existing.any { it.isActive() }) {
                    return@withTransaction AddReadingSessionResult.ActiveSessionExists
                }
                if (existing.any { it.hasSameContent(validated) }) {
                    return@withTransaction AddReadingSessionResult.Duplicate
                }
                val now = nowMillis()
                val entity =
                    ReadingSessionEntity(
                        id = idFactory(),
                        copyId = copyId,
                        status = validated.status.name,
                        startedDay = validated.startedDay?.format(),
                        finishedDay = validated.finishedDay?.format(),
                        rating = validated.rating,
                        note = validated.note,
                        createdAt = now,
                        updatedAt = now,
                    )
                dao.insert(entity)
                val mutations = mutableListOf<SyncMutation>(entity.toSyncUpsert())
                mutations += reconcileCopyStatus(copyId)
                syncJournal.record(mutations)
                AddReadingSessionResult.Added(entity.toDomain(copy.copyLabel))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AddReadingSessionResult.Failure
        }
    }

    override suspend fun updateSession(
        sessionId: String,
        draft: ReadingSessionDraft,
    ): UpdateReadingSessionResult {
        val validated =
            when (val result = validator.validate(draft)) {
                is ReadingSessionValidationResult.Invalid -> {
                    return UpdateReadingSessionResult.Invalid(result.errors)
                }

                is ReadingSessionValidationResult.Valid -> {
                    result.session
                }
            }
        return try {
            database.withTransaction {
                val previous =
                    dao.findById(sessionId)
                        ?: return@withTransaction UpdateReadingSessionResult.NotFound
                val copy =
                    database.libraryDao().findCopyById(previous.copyId)
                        ?: return@withTransaction UpdateReadingSessionResult.Failure
                val siblings = dao.findByCopyId(previous.copyId).filter { it.id != sessionId }
                if (validated.status.isActive() && siblings.any { it.isActive() }) {
                    return@withTransaction UpdateReadingSessionResult.ActiveSessionExists
                }
                if (siblings.any { it.hasSameContent(validated) }) {
                    return@withTransaction UpdateReadingSessionResult.Duplicate
                }
                val current =
                    previous.copy(
                        status = validated.status.name,
                        startedDay = validated.startedDay?.format(),
                        finishedDay = validated.finishedDay?.format(),
                        rating = validated.rating,
                        note = validated.note,
                        updatedAt = maxOf(nowMillis(), previous.updatedAt + 1),
                    )
                dao.upsert(current)
                val mutations = mutableListOf<SyncMutation>(current.toSyncUpsert())
                mutations += reconcileCopyStatus(previous.copyId)
                syncJournal.record(mutations)
                UpdateReadingSessionResult.Updated(
                    previous = previous.toDomain(copy.copyLabel),
                    current = current.toDomain(copy.copyLabel),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            UpdateReadingSessionResult.Failure
        }
    }

    override suspend fun deleteSession(sessionId: String): DeleteReadingSessionResult =
        try {
            database.withTransaction {
                val session =
                    dao.findById(sessionId)
                        ?: return@withTransaction DeleteReadingSessionResult.NotFound
                val copyLabel =
                    database
                        .libraryDao()
                        .findCopyById(session.copyId)
                        ?.copyLabel
                        .orEmpty()
                check(dao.deleteById(sessionId) == 1)
                val mutations = mutableListOf<SyncMutation>(syncDelete("readingSession", sessionId))
                mutations += reconcileCopyStatus(session.copyId)
                syncJournal.record(mutations)
                DeleteReadingSessionResult.Deleted(session.toDomain(copyLabel))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DeleteReadingSessionResult.Failure
        }

    override suspend fun restoreSession(session: ReadingSession): RestoreReadingSessionResult =
        try {
            database.withTransaction {
                if (database.libraryDao().findCopyById(session.copyId) == null) {
                    return@withTransaction RestoreReadingSessionResult.Conflict
                }
                if (dao.findById(session.id) != null) {
                    return@withTransaction RestoreReadingSessionResult.Conflict
                }
                val siblings = dao.findByCopyId(session.copyId)
                if (session.status.isActive() && siblings.any { it.isActive() }) {
                    return@withTransaction RestoreReadingSessionResult.Conflict
                }
                // 同期tombstoneが残るIDを再利用すると復元が再び削除されるため、新しいIDを採番する。
                val restoredId =
                    if (syncJournal.hasTombstone("readingSession", session.id)) {
                        idFactory()
                    } else {
                        session.id
                    }
                val entity =
                    ReadingSessionEntity(
                        id = restoredId,
                        copyId = session.copyId,
                        status = session.status.name,
                        startedDay = session.startedDay?.format(),
                        finishedDay = session.finishedDay?.format(),
                        rating = session.rating,
                        note = session.note,
                        createdAt = session.createdAt,
                        updatedAt = session.updatedAt,
                    )
                dao.insert(entity)
                val mutations = mutableListOf<SyncMutation>(entity.toSyncUpsert())
                mutations += reconcileCopyStatus(session.copyId)
                syncJournal.record(mutations)
                RestoreReadingSessionResult.Restored
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            RestoreReadingSessionResult.Failure
        }

    /**
     * コピーの readingStatus を履歴から導出して更新する。
     * 履歴0件では変更しない。変更した場合はコピーの同期upsertを返す。
     */
    private suspend fun reconcileCopyStatus(copyId: String): List<SyncMutation> {
        val copy = database.libraryDao().findCopyById(copyId) ?: return emptyList()
        val sessions = dao.findByCopyId(copyId)
        val derived = deriveReadingStatus(sessions) ?: return emptyList()
        if (derived.name == copy.readingStatus) return emptyList()
        check(dao.updateCopyReadingStatus(copyId, derived.name) == 1)
        val updated = checkNotNull(database.libraryDao().findCopyById(copyId))
        return listOf(updated.toSyncUpsert())
    }

    private fun deriveReadingStatus(sessions: List<ReadingSessionEntity>): ReadingStatus? =
        when {
            sessions.any { it.status == ReadingSessionStatus.READING.name } -> ReadingStatus.READING
            sessions.any { it.status == ReadingSessionStatus.PAUSED.name } -> ReadingStatus.PAUSED
            sessions.any { it.status == ReadingSessionStatus.FINISHED.name } -> ReadingStatus.READ
            else -> null
        }
}

private fun ReadingSessionStatus.isActive(): Boolean = this != ReadingSessionStatus.FINISHED

private fun ReadingSessionEntity.isActive(): Boolean = status != ReadingSessionStatus.FINISHED.name

private fun ReadingSessionEntity.hasSameContent(validated: ValidatedReadingSession): Boolean =
    status == validated.status.name &&
        startedDay == validated.startedDay?.format() &&
        finishedDay == validated.finishedDay?.format() &&
        rating == validated.rating &&
        note == validated.note

private fun ReadingSessionEntity.toDomain(copyLabel: String): ReadingSession =
    ReadingSession(
        id = id,
        copyId = copyId,
        copyLabel = copyLabel,
        status = status.toStatusOrDefault(),
        startedDay = startedDay?.let(PartialDate::parse),
        finishedDay = finishedDay?.let(PartialDate::parse),
        rating = rating,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

internal fun ReadingSessionRow.toDomain(): ReadingSession =
    ReadingSession(
        id = id,
        copyId = copyId,
        copyLabel = copyLabel,
        status = status.toStatusOrDefault(),
        startedDay = startedDay?.let(PartialDate::parse),
        finishedDay = finishedDay?.let(PartialDate::parse),
        rating = rating,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun String.toStatusOrDefault(): ReadingSessionStatus =
    ReadingSessionStatus.entries.firstOrNull { it.name == this } ?: ReadingSessionStatus.FINISHED
