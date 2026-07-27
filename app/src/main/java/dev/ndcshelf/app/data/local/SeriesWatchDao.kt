package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesWatchDao {
    @Query("SELECT * FROM series_watches ORDER BY seriesId")
    fun observeWatches(): Flow<List<SeriesWatchEntity>>

    @Query("SELECT * FROM series_watches ORDER BY seriesId")
    suspend fun getAllWatches(): List<SeriesWatchEntity>

    @Query("SELECT * FROM series_watches WHERE enabled = 1 ORDER BY seriesId")
    suspend fun getEnabledWatches(): List<SeriesWatchEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM series_watches WHERE enabled = 1)")
    suspend fun hasEnabledWatches(): Boolean

    @Query("SELECT COUNT(*) FROM series_watches WHERE enabled = 1")
    suspend fun countEnabledWatches(): Int

    @Query("SELECT * FROM series_watches WHERE seriesId = :seriesId LIMIT 1")
    suspend fun findWatch(seriesId: String): SeriesWatchEntity?

    @Upsert
    suspend fun upsertWatch(watch: SeriesWatchEntity)

    @Upsert
    suspend fun upsertWatches(watches: List<SeriesWatchEntity>)

    @Query("DELETE FROM series_watches")
    suspend fun deleteAllWatches()

    @Query("SELECT * FROM series_release_candidates ORDER BY id")
    suspend fun getAllCandidates(): List<SeriesReleaseCandidateEntity>

    @Query("SELECT * FROM series_release_candidates WHERE seriesId = :seriesId ORDER BY id")
    suspend fun getCandidatesForSeries(seriesId: String): List<SeriesReleaseCandidateEntity>

    @Query("SELECT * FROM series_release_candidates WHERE id = :id LIMIT 1")
    suspend fun findCandidate(id: String): SeriesReleaseCandidateEntity?

    @Query(
        """
        SELECT
            candidates.*,
            (SELECT COUNT(*) FROM owned_copies AS copies
                INNER JOIN book_editions AS editions ON editions.id = copies.editionId
                WHERE candidates.isbn13 IS NOT NULL AND editions.isbn13 = candidates.isbn13
            ) AS ownedCopyCount,
            (SELECT wishlist.status FROM wishlist_items AS wishlist
                INNER JOIN book_editions AS editions ON editions.id = wishlist.editionId
                WHERE candidates.isbn13 IS NOT NULL AND editions.isbn13 = candidates.isbn13
                LIMIT 1
            ) AS purchaseStatus
        FROM series_release_candidates AS candidates
        ORDER BY candidates.publishedDate DESC, candidates.title, candidates.id
        """,
    )
    fun observeCandidateRows(): Flow<List<SeriesReleaseCandidateRow>>

    @Upsert
    suspend fun upsertCandidate(candidate: SeriesReleaseCandidateEntity)

    @Upsert
    suspend fun upsertCandidates(candidates: List<SeriesReleaseCandidateEntity>)

    @Query(
        "UPDATE series_release_candidates SET notifiedAt = CASE " +
            "WHEN notifiedAt IS NULL THEN MAX(firstSeenAt, :notifiedAt) " +
            "WHEN notifiedAt < :notifiedAt THEN :notifiedAt ELSE notifiedAt END " +
            "WHERE id IN (:ids)",
    )
    suspend fun markNotified(ids: List<String>, notifiedAt: Long): Int

    @Query(
        "DELETE FROM series_release_candidates WHERE seriesId = :seriesId AND id NOT IN (" +
            "SELECT id FROM series_release_candidates WHERE seriesId = :seriesId " +
            "ORDER BY lastSeenAt DESC, id LIMIT :keepCount)",
    )
    suspend fun pruneCandidates(seriesId: String, keepCount: Int): Int

    @Query("DELETE FROM series_release_candidates")
    suspend fun deleteAllCandidates()
}
