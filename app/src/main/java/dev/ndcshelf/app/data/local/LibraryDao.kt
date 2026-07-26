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

    @Query("SELECT * FROM wishlist_items ORDER BY editionId")
    suspend fun getAllWishlistItems(): List<WishlistItemEntity>

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC, id ASC")
    suspend fun getAllScanSessions(): List<ScanSessionEntity>

    @Query("SELECT * FROM scan_attempts ORDER BY attemptedAt ASC, id ASC")
    suspend fun getAllScanAttempts(): List<ScanAttemptEntity>

    @Query(
        """
        SELECT
            sessions.id AS sessionId,
            sessions.startedAt AS startedAt,
            sessions.endedAt AS endedAt,
            attempts.id AS attemptId,
            attempts.isbn AS isbn,
            attempts.outcome AS outcome,
            attempts.copyId AS copyId,
            attempts.attemptedAt AS attemptedAt,
            attempts.undoneAt AS undoneAt
        FROM scan_sessions AS sessions
        LEFT JOIN scan_attempts AS attempts ON attempts.sessionId = sessions.id
        WHERE sessions.id IN (
            SELECT id FROM scan_sessions ORDER BY startedAt DESC, id ASC LIMIT :limit
        )
        ORDER BY sessions.startedAt DESC, attempts.attemptedAt DESC, attempts.id ASC
        """,
    )
    fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionAttemptRow>>

    @Query("SELECT * FROM scan_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun findActiveScanSession(): ScanSessionEntity?

    @Query("SELECT * FROM scan_attempts WHERE id = :attemptId LIMIT 1")
    suspend fun findScanAttempt(attemptId: String): ScanAttemptEntity?

    @Query(
        "SELECT * FROM scan_attempts WHERE sessionId = :sessionId ORDER BY attemptedAt ASC, id ASC",
    )
    suspend fun findScanAttemptsBySession(sessionId: String): List<ScanAttemptEntity>

    @Query(
        """
        SELECT
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
            editions.bibliographicSource AS bibliographicSource,
            wishlist.status AS status,
            wishlist.createdAt AS createdAt,
            wishlist.updatedAt AS updatedAt,
            (SELECT COUNT(*) FROM owned_copies WHERE editionId = editions.id) AS ownedCopyCount
        FROM wishlist_items AS wishlist
        INNER JOIN book_editions AS editions ON editions.id = wishlist.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        ORDER BY wishlist.updatedAt DESC, wishlist.editionId ASC
        """,
    )
    fun observeWishlist(): Flow<List<WishlistBookRow>>

    @Query(
        """
        SELECT
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
            editions.bibliographicSource AS bibliographicSource,
            wishlist.status AS status,
            wishlist.createdAt AS createdAt,
            wishlist.updatedAt AS updatedAt,
            (SELECT COUNT(*) FROM owned_copies WHERE editionId = editions.id) AS ownedCopyCount
        FROM wishlist_items AS wishlist
        INNER JOIN book_editions AS editions ON editions.id = wishlist.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        WHERE editions.isbn13 = :isbn13
        LIMIT 1
        """,
    )
    suspend fun findWishlistByIsbn(isbn13: String): WishlistBookRow?

    @Query("SELECT * FROM wishlist_items WHERE editionId = :editionId LIMIT 1")
    suspend fun findWishlistByEditionId(editionId: String): WishlistItemEntity?

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
            editions.bibliographicSource AS bibliographicSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey,
            copies.copyLabel AS copyLabel
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
            editions.bibliographicSource AS bibliographicSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey,
            copies.copyLabel AS copyLabel
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
            editions.bibliographicSource AS bibliographicSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey,
            copies.copyLabel AS copyLabel
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
            editions.bibliographicSource AS bibliographicSource,
            copies.mediaType AS mediaType,
            CASE WHEN tiers.id IS NULL THEN copies.location
                ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
            END AS location,
            copies.tierId AS locationTierId,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt,
            copies.shelfOrderKey AS shelfOrderKey,
            copies.copyLabel AS copyLabel
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScanSession(session: ScanSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScanAttempt(attempt: ScanAttemptEntity)

    @Upsert
    suspend fun upsertWishlistItem(item: WishlistItemEntity)

    @Upsert
    suspend fun upsertWishlistItems(items: List<WishlistItemEntity>)

    @Upsert
    suspend fun upsertWorks(works: List<BookWorkEntity>)

    @Upsert
    suspend fun upsertEditions(editions: List<BookEditionEntity>)

    @Upsert
    suspend fun upsertCopies(copies: List<OwnedCopyEntity>)

    @Upsert
    suspend fun upsertScanSessions(sessions: List<ScanSessionEntity>)

    @Upsert
    suspend fun upsertScanAttempts(attempts: List<ScanAttemptEntity>)

    @Query("DELETE FROM scan_attempts")
    suspend fun deleteAllScanAttempts()

    @Query("DELETE FROM scan_sessions")
    suspend fun deleteAllScanSessions()

    @Query("DELETE FROM owned_copies")
    suspend fun deleteAllCopies()

    @Query("DELETE FROM wishlist_items")
    suspend fun deleteAllWishlistItems()

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
            copyLabel = :copyLabel,
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
        copyLabel: String = "所蔵本",
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

    @Query(
        """
        UPDATE book_editions
        SET isbn13 = :isbn13,
            publisher = :publisher,
            publishedYear = :publishedYear,
            coverUrl = :coverUrl,
            ndcCode = :ndcCode,
            ndcEdition = :ndcEdition,
            classificationSource = :classificationSource,
            bibliographicSource = 'NDL'
        WHERE id = :editionId AND bibliographicSource = 'MANUAL'
        """,
    )
    suspend fun reconcileManualEdition(
        editionId: String,
        isbn13: String,
        publisher: String?,
        publishedYear: Int?,
        coverUrl: String?,
        ndcCode: String?,
        ndcEdition: String?,
        classificationSource: String,
    ): Int

    @Query("UPDATE owned_copies SET editionId = :targetEditionId WHERE editionId = :sourceEditionId")
    suspend fun moveCopiesToEdition(sourceEditionId: String, targetEditionId: String): Int

    @Query("DELETE FROM owned_copies WHERE id = :copyId")
    suspend fun deleteCopyById(copyId: String): Int

    @Query("SELECT COUNT(*) FROM owned_copies WHERE editionId = :editionId")
    suspend fun countCopiesForEdition(editionId: String): Int

    @Query("DELETE FROM wishlist_items WHERE editionId = :editionId")
    suspend fun deleteWishlistByEditionId(editionId: String): Int

    @Query("DELETE FROM book_editions WHERE id = :editionId")
    suspend fun deleteEditionById(editionId: String): Int

    @Query("SELECT COUNT(*) FROM book_editions WHERE workId = :workId")
    suspend fun countEditionsForWork(workId: String): Int

    @Query("DELETE FROM book_works WHERE id = :workId")
    suspend fun deleteWorkById(workId: String): Int

    @Query("UPDATE scan_sessions SET endedAt = :endedAt WHERE endedAt IS NULL")
    suspend fun finishActiveScanSessions(endedAt: Long): Int

    @Query("UPDATE scan_sessions SET endedAt = :endedAt WHERE id = :sessionId AND endedAt IS NULL")
    suspend fun finishScanSession(sessionId: String, endedAt: Long): Int

    @Query("UPDATE scan_attempts SET undoneAt = :undoneAt WHERE id = :attemptId AND undoneAt IS NULL")
    suspend fun markScanAttemptUndone(attemptId: String, undoneAt: Long): Int

    @Query(
        """
        DELETE FROM scan_sessions
        WHERE endedAt IS NOT NULL
          AND id NOT IN (
              SELECT id FROM scan_sessions ORDER BY startedAt DESC, id ASC LIMIT :keepCount
          )
        """,
    )
    suspend fun pruneScanSessions(keepCount: Int): Int
}
