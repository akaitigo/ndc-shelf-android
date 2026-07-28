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

    @Query("SELECT * FROM series WHERE id = :seriesId LIMIT 1")
    suspend fun findSeriesById(seriesId: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE name = :name ORDER BY id LIMIT 1")
    suspend fun findSeriesByName(name: String): SeriesEntity?

    @Query(
        "SELECT * FROM series_memberships WHERE seriesId = :seriesId " +
            "ORDER BY sortOrderKey, id",
    )
    suspend fun getMembershipsForSeries(seriesId: String): List<SeriesMembershipEntity>

    @Query("SELECT * FROM series_memberships WHERE id = :membershipId LIMIT 1")
    suspend fun findMembershipById(membershipId: String): SeriesMembershipEntity?

    @Query(
        """
        SELECT works.* FROM book_works AS works
        WHERE NOT EXISTS (
            SELECT 1 FROM series_memberships AS memberships
            WHERE memberships.workId = works.id
        )
        ORDER BY works.title, works.id
        """,
    )
    fun observeUnassignedWorks(): Flow<List<BookWorkEntity>>

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
            memberships.updatedAt AS updatedAt,
            memberships.origin AS origin,
            memberships.confirmedBy AS confirmedBy,
            memberships.sourceTitle AS sourceTitle
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
            memberships.updatedAt AS updatedAt,
            memberships.origin AS origin,
            memberships.confirmedBy AS confirmedBy,
            memberships.sourceTitle AS sourceTitle
        FROM series_memberships AS memberships
        INNER JOIN book_works AS works ON works.id = memberships.workId
        WHERE memberships.workId = :workId
        ORDER BY memberships.seriesId, memberships.sortOrderKey, memberships.id
        """,
    )
    suspend fun findMembershipsForWork(workId: String): List<SeriesMembershipRow>

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
            memberships.updatedAt AS updatedAt,
            memberships.origin AS origin,
            memberships.confirmedBy AS confirmedBy,
            memberships.sourceTitle AS sourceTitle,
            MIN(CASE WHEN copies.id IS NOT NULL THEN editions.id END) AS ownedEditionId,
            MIN(editions.isbn13) AS bookstoreIsbn,
            COUNT(DISTINCT copies.id) AS ownedCopyCount,
            COUNT(DISTINCT CASE WHEN copies.readingStatus = 'READ' THEN copies.id END)
                AS readCopyCount,
            COUNT(DISTINCT CASE WHEN copies.readingStatus = 'READING' THEN copies.id END)
                AS readingCopyCount,
            COALESCE(MAX(CASE wishlist.status
                WHEN 'RESERVED' THEN 2
                WHEN 'WANTED' THEN 1
                ELSE 0
            END), 0) AS purchaseStatusRank,
            MAX(copies.addedAt) AS latestOwnedAddedAt
        FROM series_memberships AS memberships
        INNER JOIN book_works AS works ON works.id = memberships.workId
        LEFT JOIN book_editions AS editions ON editions.workId = memberships.workId
        LEFT JOIN owned_copies AS copies ON copies.editionId = editions.id
        LEFT JOIN wishlist_items AS wishlist ON wishlist.editionId = editions.id
        GROUP BY memberships.id
        ORDER BY memberships.seriesId, memberships.sortOrderKey, memberships.id
        """,
    )
    fun observeAllVolumes(): Flow<List<SeriesVolumeRow>>

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
