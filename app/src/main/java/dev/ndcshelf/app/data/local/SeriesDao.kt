package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY name, id")
    fun observeSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series ORDER BY id")
    suspend fun getAllSeries(): List<SeriesEntity>

    @Query("SELECT * FROM series_memberships ORDER BY id")
    suspend fun getAllMemberships(): List<SeriesMembershipEntity>

    @Query(
        """
        SELECT
            memberships.id AS membershipId,
            memberships.seriesId AS seriesId,
            memberships.workId AS workId,
            works.title AS workTitle,
            works.primaryAuthor AS primaryAuthor,
            memberships.sortOrderKey AS sortOrderKey,
            memberships.volumeLabel AS volumeLabel,
            memberships.type AS type,
            memberships.createdAt AS createdAt,
            memberships.updatedAt AS updatedAt
        FROM series_memberships AS memberships
        INNER JOIN book_works AS works ON works.id = memberships.workId
        WHERE memberships.seriesId = :seriesId
        ORDER BY memberships.sortOrderKey, memberships.id
        """,
    )
    fun observeMemberships(seriesId: String): Flow<List<SeriesMembershipRow>>

    @Query(
        """
        SELECT
            memberships.id AS membershipId,
            memberships.seriesId AS seriesId,
            memberships.workId AS workId,
            works.title AS workTitle,
            works.primaryAuthor AS primaryAuthor,
            memberships.sortOrderKey AS sortOrderKey,
            memberships.volumeLabel AS volumeLabel,
            memberships.type AS type,
            memberships.createdAt AS createdAt,
            memberships.updatedAt AS updatedAt
        FROM series_memberships AS memberships
        INNER JOIN book_works AS works ON works.id = memberships.workId
        WHERE memberships.workId = :workId
        ORDER BY memberships.seriesId, memberships.sortOrderKey, memberships.id
        """,
    )
    suspend fun findMembershipsForWork(workId: String): List<SeriesMembershipRow>

    @Upsert
    suspend fun upsertSeries(series: SeriesEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMembership(membership: SeriesMembershipEntity)

    @Upsert
    suspend fun upsertSeriesItems(series: List<SeriesEntity>)

    @Upsert
    suspend fun upsertMemberships(memberships: List<SeriesMembershipEntity>)

    @Query(
        """
        UPDATE series_memberships
        SET seriesId = :seriesId,
            sortOrderKey = :sortOrderKey,
            volumeLabel = :volumeLabel,
            type = :type,
            updatedAt = :updatedAt
        WHERE id = :membershipId
        """,
    )
    suspend fun updateMembership(
        membershipId: String,
        seriesId: String,
        sortOrderKey: String,
        volumeLabel: String,
        type: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM series_memberships WHERE id = :membershipId")
    suspend fun deleteMembership(membershipId: String): Int

    @Query("DELETE FROM series_memberships")
    suspend fun deleteAllMemberships()

    @Query("DELETE FROM series")
    suspend fun deleteAllSeries()

    @Query("DELETE FROM series WHERE id = :seriesId")
    suspend fun deleteSeries(seriesId: String): Int
}
