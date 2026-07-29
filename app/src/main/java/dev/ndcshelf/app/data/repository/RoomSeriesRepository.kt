package dev.ndcshelf.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.SeriesMembershipEntity
import dev.ndcshelf.app.data.local.SeriesVolumeRow
import dev.ndcshelf.app.domain.model.SeriesMembershipConfirmer
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesSuggestion
import dev.ndcshelf.app.domain.model.SeriesSuggestionParser
import dev.ndcshelf.app.domain.model.SeriesVolume
import dev.ndcshelf.app.domain.repository.SeriesConfirmationDraft
import dev.ndcshelf.app.domain.repository.SeriesConfirmationResult
import dev.ndcshelf.app.domain.repository.SeriesConfirmationTarget
import dev.ndcshelf.app.domain.repository.SeriesRepository
import dev.ndcshelf.app.domain.sync.SyncMutationJournal
import dev.ndcshelf.app.data.sync.syncDelete
import dev.ndcshelf.app.data.sync.toSyncUpsert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomSeriesRepository(
    private val database: AppDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncJournal: SyncMutationJournal = SyncMutationJournal.Disabled,
) : SeriesRepository {
    private val dao = database.seriesDao()

    override fun observeCatalog(): Flow<List<SeriesOverview>> = combine(
        dao.observeSeries(),
        dao.observeAllVolumes(),
    ) { series, rows ->
        val volumesBySeries = rows.map(SeriesVolumeRow::toVolume).groupBy { it.membership.seriesId }
        series.map { item ->
            SeriesOverview(
                series = item.toDomain(),
                volumes = volumesBySeries[item.id].orEmpty(),
            )
        }
    }

    override fun observeSuggestions(): Flow<List<SeriesSuggestion>> =
        dao.observeUnassignedWorks().map { works ->
            works.mapNotNull { work -> SeriesSuggestionParser.suggest(work.id, work.title) }
        }

    override suspend fun suggestionFor(workId: String): SeriesSuggestion? {
        val work = database.libraryDao().findWorkById(workId) ?: return null
        return SeriesSuggestionParser.suggest(work.id, work.title)
            ?: SeriesSuggestionParser.manual(work.id, work.title)
    }

    override suspend fun confirm(
        target: SeriesConfirmationTarget,
        drafts: List<SeriesConfirmationDraft>,
    ): SeriesConfirmationResult {
        if (!drafts.areValid()) return SeriesConfirmationResult.Invalid
        return try {
            database.withTransaction {
                val now = nowMillis()
                val existingSeries = when (target) {
                    is SeriesConfirmationTarget.Existing -> dao.findSeriesById(target.seriesId)
                        ?: return@withTransaction SeriesConfirmationResult.Invalid
                    is SeriesConfirmationTarget.New -> {
                        val name = target.name.trim()
                        if (name.isBlank() || name.length > MAX_SERIES_NAME_LENGTH) {
                            return@withTransaction SeriesConfirmationResult.Invalid
                        }
                        if (dao.findSeriesByName(name) != null) {
                            return@withTransaction SeriesConfirmationResult.Conflict
                        }
                        null
                    }
                }
                val prospectiveSeriesId = existingSeries?.id ?: idFactory()
                val workDao = database.libraryDao()
                for (draft in drafts) {
                    val work = workDao.findWorkById(draft.workId)
                        ?: return@withTransaction SeriesConfirmationResult.Invalid
                    if (draft.sourceTitle != work.title.trim() || dao.findMembershipsForWork(draft.workId)
                            .any { it.seriesId == prospectiveSeriesId }
                    ) {
                        return@withTransaction SeriesConfirmationResult.Conflict
                    }
                }
                val series = existingSeries ?: SeriesEntity(
                    id = prospectiveSeriesId,
                    name = (target as SeriesConfirmationTarget.New).name.trim(),
                    createdAt = now,
                    updatedAt = now,
                ).also { dao.upsertSeries(it) }
                var left = dao.getMembershipsForSeries(series.id).lastOrNull()?.sortOrderKey
                val memberships = drafts.map { draft ->
                    val id = idFactory()
                    val orderKey = FractionalOrderKey.between(left, null, id)
                    val membership = SeriesMembershipEntity(
                            id = id,
                            seriesId = series.id,
                            workId = draft.workId,
                            sortOrderKey = orderKey,
                            volumeLabel = draft.volumeLabel.trim(),
                            type = draft.type.name,
                            createdAt = now,
                            updatedAt = now,
                            origin = draft.origin.name,
                            confirmedBy = SeriesMembershipConfirmer.USER.name,
                            sourceTitle = draft.sourceTitle,
                        )
                    dao.insertMembership(membership)
                    left = orderKey
                    membership
                }
                val updatedSeries = series.copy(updatedAt = now)
                dao.upsertSeries(updatedSeries)
                syncJournal.record(listOf(updatedSeries.toSyncUpsert()) + memberships.map { it.toSyncUpsert() })
                SeriesConfirmationResult.Confirmed(series.id, memberships.map(SeriesMembershipEntity::id))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteConstraintException) {
            SeriesConfirmationResult.Conflict
        } catch (_: Exception) {
            SeriesConfirmationResult.Failure
        }
    }

    override suspend fun removeMembership(membershipId: String): Boolean = try {
        database.withTransaction {
            val membership = dao.findMembershipById(membershipId) ?: return@withTransaction false
            if (dao.deleteMembership(membershipId) != 1) return@withTransaction false
            val updated = dao.findSeriesById(membership.seriesId)?.copy(updatedAt = nowMillis())
            if (updated != null) dao.upsertSeries(updated)
            syncJournal.record(
                listOf(syncDelete("seriesMembership", membershipId)) +
                    listOfNotNull(updated?.toSyncUpsert()),
            )
            true
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    private fun List<SeriesConfirmationDraft>.areValid(): Boolean =
        isNotEmpty() && size <= MAX_CONFIRMATION_BATCH_SIZE &&
            map(SeriesConfirmationDraft::workId).distinct().size == size &&
            all { draft ->
                draft.workId.isNotBlank() &&
                    draft.volumeLabel.isNotBlank() &&
                    draft.volumeLabel.length <= MAX_VOLUME_LABEL_LENGTH &&
                    draft.sourceTitle.isNotBlank() &&
                    draft.sourceTitle.length <= MAX_SOURCE_TITLE_LENGTH
            }

    private companion object {
        const val MAX_CONFIRMATION_BATCH_SIZE = 500
        const val MAX_SERIES_NAME_LENGTH = 200
        const val MAX_VOLUME_LABEL_LENGTH = 80
        const val MAX_SOURCE_TITLE_LENGTH = 500
    }
}

internal fun SeriesVolumeRow.toVolume(): SeriesVolume = SeriesVolume(
    membership = toMembershipDomain(),
    ownedEditionId = ownedEditionId,
    bookstoreIsbn = bookstoreIsbn,
    ownedCopyCount = ownedCopyCount,
    readCopyCount = readCopyCount,
    readingCopyCount = readingCopyCount,
    purchaseStatus = when (purchaseStatusRank) {
        2 -> PurchaseStatus.RESERVED
        1 -> PurchaseStatus.WANTED
        else -> null
    },
    latestOwnedAddedAt = latestOwnedAddedAt,
)

private fun SeriesVolumeRow.toMembershipDomain() =
    dev.ndcshelf.app.domain.model.SeriesMembership(
        id = membershipId,
        seriesId = seriesId,
        workId = workId,
        workTitle = workTitle,
        primaryAuthor = primaryAuthor,
        sortOrderKey = sortOrderKey,
        volumeLabel = volumeLabel,
        type = enumValueOf(type),
        createdAt = createdAt,
        updatedAt = updatedAt,
        origin = dev.ndcshelf.app.domain.model.SeriesMembershipOrigin.valueOf(origin),
        confirmedBy = dev.ndcshelf.app.domain.model.SeriesMembershipConfirmer.valueOf(confirmedBy),
        sourceTitle = sourceTitle,
    )
