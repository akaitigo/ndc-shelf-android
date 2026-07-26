package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM book_works ORDER BY id")
    suspend fun getAllWorks(): List<BookWorkEntity>

    @Query("SELECT * FROM book_editions ORDER BY id")
    suspend fun getAllEditions(): List<BookEditionEntity>

    @Query("SELECT * FROM owned_copies ORDER BY id")
    suspend fun getAllCopies(): List<OwnedCopyEntity>

    @Query(
        """
        SELECT
            copies.id AS copyId,
            works.id AS workId,
            editions.id AS editionId,
            works.title AS title,
            works.primaryAuthor AS primaryAuthor,
            editions.isbn13 AS isbn13,
            editions.publisher AS publisher,
            editions.publishedYear AS publishedYear,
            editions.coverUrl AS coverUrl,
            editions.ndcCode AS ndcCode,
            editions.ndcEdition AS ndcEdition,
            editions.classificationSource AS classificationSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey
        FROM owned_copies AS copies
        INNER JOIN book_editions AS editions ON editions.id = copies.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        LEFT JOIN location_tiers AS tiers ON tiers.id = copies.tierId
        LEFT JOIN location_shelves AS shelves ON shelves.id = tiers.shelfId
        LEFT JOIN location_rooms AS rooms ON rooms.id = shelves.roomId
        ORDER BY copies.addedAt DESC, copies.id ASC
        """,
    )
    fun observeLibrary(): Flow<List<LibraryBookRow>>

    @Query(
        """
        SELECT
            copies.id AS copyId,
            works.id AS workId,
            editions.id AS editionId,
            works.title AS title,
            works.primaryAuthor AS primaryAuthor,
            editions.isbn13 AS isbn13,
            editions.publisher AS publisher,
            editions.publishedYear AS publishedYear,
            editions.coverUrl AS coverUrl,
            editions.ndcCode AS ndcCode,
            editions.ndcEdition AS ndcEdition,
            editions.classificationSource AS classificationSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey
        FROM owned_copies AS copies
        INNER JOIN book_editions AS editions ON editions.id = copies.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        LEFT JOIN location_tiers AS tiers ON tiers.id = copies.tierId
        LEFT JOIN location_shelves AS shelves ON shelves.id = tiers.shelfId
        LEFT JOIN location_rooms AS rooms ON rooms.id = shelves.roomId
        ORDER BY copies.addedAt DESC, copies.id ASC
        """,
    )
    suspend fun getLibrary(): List<LibraryBookRow>

    @Query(
        """
        SELECT
            copies.id AS copyId,
            works.id AS workId,
            editions.id AS editionId,
            works.title AS title,
            works.primaryAuthor AS primaryAuthor,
            editions.isbn13 AS isbn13,
            editions.publisher AS publisher,
            editions.publishedYear AS publishedYear,
            editions.coverUrl AS coverUrl,
            editions.ndcCode AS ndcCode,
            editions.ndcEdition AS ndcEdition,
            editions.classificationSource AS classificationSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey
        FROM owned_copies AS copies
        INNER JOIN book_editions AS editions ON editions.id = copies.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        LEFT JOIN location_tiers AS tiers ON tiers.id = copies.tierId
        LEFT JOIN location_shelves AS shelves ON shelves.id = tiers.shelfId
        LEFT JOIN location_rooms AS rooms ON rooms.id = shelves.roomId
        WHERE editions.isbn13 = :isbn13
        LIMIT 1
        """,
    )
    suspend fun findOwnedByIsbn(isbn13: String): LibraryBookRow?

    @Query(
        """
        SELECT
            copies.id AS copyId,
            works.id AS workId,
            editions.id AS editionId,
            works.title AS title,
            works.primaryAuthor AS primaryAuthor,
            editions.isbn13 AS isbn13,
            editions.publisher AS publisher,
            editions.publishedYear AS publishedYear,
            editions.coverUrl AS coverUrl,
            editions.ndcCode AS ndcCode,
            editions.ndcEdition AS ndcEdition,
            editions.classificationSource AS classificationSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey
        FROM owned_copies AS copies
        INNER JOIN book_editions AS editions ON editions.id = copies.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        LEFT JOIN location_tiers AS tiers ON tiers.id = copies.tierId
        LEFT JOIN location_shelves AS shelves ON shelves.id = tiers.shelfId
        LEFT JOIN location_rooms AS rooms ON rooms.id = shelves.roomId
        WHERE copies.id = :copyId
        LIMIT 1
        """,
    )
    suspend fun findOwnedByCopyId(copyId: String): LibraryBookRow?

    @Query("SELECT * FROM book_works WHERE id = :workId LIMIT 1")
    suspend fun findWorkById(workId: String): BookWorkEntity?

    @Query("SELECT * FROM book_editions WHERE id = :editionId LIMIT 1")
    suspend fun findEditionById(editionId: String): BookEditionEntity?

    @Query("SELECT * FROM book_editions WHERE isbn13 = :isbn13 LIMIT 1")
    suspend fun findEditionByIsbn(isbn13: String): BookEditionEntity?

    @Query("SELECT * FROM owned_copies WHERE id = :copyId LIMIT 1")
    suspend fun findCopyById(copyId: String): OwnedCopyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWork(work: BookWorkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEdition(edition: BookEditionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCopy(copy: OwnedCopyEntity)

    @Upsert
    suspend fun upsertWorks(works: List<BookWorkEntity>)

    @Upsert
    suspend fun upsertEditions(editions: List<BookEditionEntity>)

    @Upsert
    suspend fun upsertCopies(copies: List<OwnedCopyEntity>)

    @Query("DELETE FROM owned_copies")
    suspend fun deleteAllCopies()

    @Query("DELETE FROM book_editions")
    suspend fun deleteAllEditions()

    @Query("DELETE FROM book_works")
    suspend fun deleteAllWorks()

    @Query(
        """
        UPDATE owned_copies
        SET location = :location,
            tierId = :tierId,
            shelfOrderKey = :shelfOrderKey,
            readingStatus = :readingStatus
        WHERE id = :copyId
        """,
    )
    suspend fun updateCopy(
        copyId: String,
        location: String,
        readingStatus: String,
        tierId: String? = null,
        shelfOrderKey: String? = null,
    )

    @Query("UPDATE book_works SET title = :title, primaryAuthor = :primaryAuthor WHERE id = :workId")
    suspend fun updateWork(workId: String, title: String, primaryAuthor: String)

    @Query(
        """
        UPDATE book_editions
        SET publisher = :publisher,
            publishedYear = :publishedYear,
            ndcCode = :ndcCode,
            ndcEdition = :ndcEdition,
            classificationSource = :classificationSource
        WHERE id = :editionId
        """,
    )
    suspend fun updateEdition(
        editionId: String,
        publisher: String?,
        publishedYear: Int?,
        ndcCode: String?,
        ndcEdition: String?,
        classificationSource: String,
    )

    @Query("DELETE FROM owned_copies WHERE id = :copyId")
    suspend fun deleteCopyById(copyId: String): Int

    @Query("SELECT COUNT(*) FROM owned_copies WHERE editionId = :editionId")
    suspend fun countCopiesForEdition(editionId: String): Int

    @Query("DELETE FROM book_editions WHERE id = :editionId")
    suspend fun deleteEditionById(editionId: String): Int

    @Query("SELECT COUNT(*) FROM book_editions WHERE workId = :workId")
    suspend fun countEditionsForWork(workId: String): Int

    @Query("DELETE FROM book_works WHERE id = :workId")
    suspend fun deleteWorkById(workId: String): Int
}
