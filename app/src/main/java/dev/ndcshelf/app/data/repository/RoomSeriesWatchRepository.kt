package dev.ndcshelf.app.data.repository

import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SeriesReleaseCandidateEntity
import dev.ndcshelf.app.data.local.SeriesReleaseCandidateRow
import dev.ndcshelf.app.data.local.SeriesWatchEntity
import dev.ndcshelf.app.data.remote.SeriesReleaseSource
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceCandidate
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceResult
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.SeriesReleaseCandidate
import dev.ndcshelf.app.domain.model.SeriesReleaseState
import dev.ndcshelf.app.domain.model.SeriesWatch
import dev.ndcshelf.app.domain.model.SeriesWatchOverview
import dev.ndcshelf.app.domain.repository.SeriesReleaseNotification
import dev.ndcshelf.app.domain.repository.SeriesWatchCheckResult
import dev.ndcshelf.app.domain.repository.SeriesWatchMutationResult
import dev.ndcshelf.app.domain.repository.SeriesWatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.security.MessageDigest
import java.util.Calendar

class RoomSeriesWatchRepository(
    private val database: AppDatabase,
    private val source: SeriesReleaseSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val currentYear: () -> Int = { Calendar.getInstance().get(Calendar.YEAR) },
) : SeriesWatchRepository {
    private val dao = database.seriesWatchDao()

    override fun observeWatches(): Flow<List<SeriesWatchOverview>> = combine(
        dao.observeWatches(),
        dao.observeCandidateRows(),
    ) { watches, candidates ->
        val rows = candidates.groupBy(SeriesReleaseCandidateRow::seriesId)
        watches.map { SeriesWatchOverview(it.toDomain(), rows[it.seriesId].orEmpty().map { row -> row.toDomain() }) }
    }

    override suspend fun setEnabled(seriesId: String, enabled: Boolean): SeriesWatchMutationResult = try {
        database.withTransaction {
            val series = database.seriesDao().findSeriesById(seriesId)
                ?: return@withTransaction SeriesWatchMutationResult.Invalid
            val title = series.name.trim()
            if (title.isBlank() || title.length > MAX_QUERY_CHARACTERS) {
                return@withTransaction SeriesWatchMutationResult.Invalid
            }
            val existing = dao.findWatch(seriesId)
            if (enabled && existing?.enabled != true &&
                dao.countEnabledWatches() >= MAX_WATCHES_PER_RUN
            ) {
                return@withTransaction SeriesWatchMutationResult.Invalid
            }
            val now = nowMillis()
            dao.upsertWatch(
                existing?.copy(
                    queryTitle = title,
                    enabled = enabled,
                    updatedAt = maxOf(existing.updatedAt, now),
                ) ?: SeriesWatchEntity(seriesId, title, enabled, now, now, null, null),
            )
            SeriesWatchMutationResult.Updated
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        SeriesWatchMutationResult.Failure
    }

    override suspend fun hasEnabledWatches(): Boolean = dao.hasEnabledWatches()

    override suspend fun checkEnabledWatches(): SeriesWatchCheckResult {
        val notifications = mutableListOf<SeriesReleaseNotification>()
        var failed = false
        var retryable = false
        val now = nowMillis()
        for (watch in dao.getEnabledWatches()) {
            if (!isDue(watch.lastSuccessfulAt, now)) continue
            when (val result = source.search(watch.queryTitle, currentYear() - LOOKBACK_YEARS)) {
                is SeriesReleaseSourceResult.Failure -> {
                    updateCheckedAt(watch)
                    failed = true
                    retryable = retryable || result.reason.retryable
                    if (result.reason.retryable) break
                }
                is SeriesReleaseSourceResult.Found -> processFound(watch, result.candidates)?.let(notifications::add)
            }
        }
        return if (failed) SeriesWatchCheckResult.PartialFailure(notifications, retryable)
        else SeriesWatchCheckResult.Success(notifications)
    }

    override suspend fun markNotified(candidateIds: List<String>) {
        if (candidateIds.isNotEmpty()) dao.markNotified(candidateIds.distinct(), nowMillis())
    }

    private suspend fun updateCheckedAt(watch: SeriesWatchEntity) = database.withTransaction {
        val current = dao.findWatch(watch.seriesId) ?: return@withTransaction
        dao.upsertWatch(current.copy(lastCheckedAt = monotonic(current.lastCheckedAt, nowMillis())))
    }

    private suspend fun processFound(
        watch: SeriesWatchEntity,
        candidates: List<SeriesReleaseSourceCandidate>,
    ): SeriesReleaseNotification? = database.withTransaction {
        val current = dao.findWatch(watch.seriesId)?.takeIf(SeriesWatchEntity::enabled)
            ?: return@withTransaction null
        val now = nowMillis()
        val baseline = current.lastSuccessfulAt == null
        val fresh = mutableListOf<SeriesReleaseCandidateEntity>()
        candidates.asSequence()
            .filter { it.matches(current.queryTitle) }
            .distinctBy { it.stableSourceId() }
            .take(MAX_CANDIDATES_PER_RESPONSE)
            .forEach { candidate ->
                val sourceId = candidate.stableSourceId()
                val id = candidateId(current.seriesId, sourceId)
                val existing = dao.findCandidate(id)
                val entity = SeriesReleaseCandidateEntity(
                    id = id,
                    seriesId = current.seriesId,
                    sourceRecordId = sourceId,
                    title = candidate.title.trim().take(MAX_TITLE_CHARACTERS),
                    primaryAuthor = candidate.primaryAuthor.trim().take(MAX_AUTHOR_CHARACTERS),
                    isbn13 = candidate.isbn13,
                    publisher = candidate.publisher?.trim()?.take(MAX_PUBLISHER_CHARACTERS),
                    publishedDate = candidate.publishedDate,
                    firstSeenAt = existing?.firstSeenAt ?: now,
                    lastSeenAt = maxOf(existing?.lastSeenAt ?: now, now),
                    // Baseline records are intentionally suppressed; later unnotified records retry delivery.
                    notifiedAt = existing?.notifiedAt ?: now.takeIf { baseline },
                )
                dao.upsertCandidate(entity)
                if (!baseline && entity.notifiedAt == null && !isAlreadyTracked(candidate.isbn13)) fresh += entity
            }
        dao.upsertWatch(
            current.copy(
                lastCheckedAt = monotonic(current.lastCheckedAt, now),
                lastSuccessfulAt = monotonic(current.lastSuccessfulAt, now),
            ),
        )
        dao.pruneCandidates(current.seriesId, MAX_STORED_CANDIDATES)
        fresh.takeIf(List<*>::isNotEmpty)?.let {
            SeriesReleaseNotification(
                seriesId = current.seriesId,
                seriesTitle = current.queryTitle,
                candidateIds = it.map(SeriesReleaseCandidateEntity::id),
                candidateTitles = it.map(SeriesReleaseCandidateEntity::title),
            )
        }
    }

    private suspend fun isAlreadyTracked(isbn13: String?): Boolean = isbn13 != null &&
        (database.libraryDao().findOwnedByIsbn(isbn13) != null ||
            database.libraryDao().findWishlistByIsbn(isbn13) != null)

    private fun Long?.letOr(value: Long): Long = this?.let { maxOf(it, value) } ?: value
    private fun monotonic(previous: Long?, next: Long): Long = previous.letOr(next)
    private fun isDue(lastSuccessfulAt: Long?, now: Long): Boolean =
        lastSuccessfulAt == null ||
            now >= lastSuccessfulAt && now - lastSuccessfulAt >= MIN_SUCCESS_INTERVAL_MILLIS

    private companion object {
        const val LOOKBACK_YEARS = 1
        const val MAX_WATCHES_PER_RUN = 100
        const val MAX_CANDIDATES_PER_RESPONSE = 20
        const val MAX_STORED_CANDIDATES = 200
        const val MAX_QUERY_CHARACTERS = 200
        const val MAX_TITLE_CHARACTERS = 500
        const val MAX_AUTHOR_CHARACTERS = 500
        const val MAX_PUBLISHER_CHARACTERS = 300
        const val MIN_SUCCESS_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1_000
    }
}

private fun SeriesReleaseSourceCandidate.matches(query: String): Boolean {
    val expected = query.normalizeSeriesTitle()
    val actual = title.normalizeSeriesTitle()
    return expected.isNotBlank() && (actual.contains(expected) || expected.contains(actual))
}

private fun String.normalizeSeriesTitle(): String = lowercase()
    .filterNot { it.isWhitespace() || it in "・:：-－_()（）[]［］" }

private fun SeriesReleaseSourceCandidate.stableSourceId(): String {
    val sourceId = sourceRecordId.trim()
    return when {
        sourceId.isBlank() -> isbn13 ?: bibliographicFingerprint()
        sourceId.length <= MAX_SOURCE_RECORD_ID_CHARACTERS -> sourceId
        else -> sourceId.sha256()
    }
}

private fun SeriesReleaseSourceCandidate.bibliographicFingerprint(): String =
    listOf(title, primaryAuthor, publisher.orEmpty(), publishedDate.orEmpty()).joinToString("|").sha256()

private fun candidateId(seriesId: String, sourceId: String): String = "$seriesId|$sourceId".sha256()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun SeriesWatchEntity.toDomain() = SeriesWatch(
    seriesId, queryTitle, enabled, createdAt, updatedAt, lastCheckedAt, lastSuccessfulAt,
)

private fun SeriesReleaseCandidateRow.toDomain() = SeriesReleaseCandidate(
    id = id,
    seriesId = seriesId,
    title = title,
    primaryAuthor = primaryAuthor,
    isbn13 = isbn13,
    publisher = publisher,
    publishedDate = publishedDate,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    notifiedAt = notifiedAt,
    state = when {
        ownedCopyCount > 0 -> SeriesReleaseState.OWNED
        purchaseStatus == PurchaseStatus.RESERVED.name -> SeriesReleaseState.RESERVED
        purchaseStatus == PurchaseStatus.WANTED.name -> SeriesReleaseState.WANTED
        else -> SeriesReleaseState.NEW
    },
)

private const val MAX_SOURCE_RECORD_ID_CHARACTERS = 500
