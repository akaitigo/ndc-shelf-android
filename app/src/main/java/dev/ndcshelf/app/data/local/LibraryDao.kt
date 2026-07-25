package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
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
            copies.location AS location,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt
        FROM owned_copies AS copies
        INNER JOIN book_editions AS editions ON editions.id = copies.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        ORDER BY copies.addedAt DESC
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
            copies.location AS location,
            copies.readingStatus AS readingStatus,
            copies.addedAt AS addedAt
        FROM owned_copies AS copies
        INNER JOIN book_editions AS editions ON editions.id = copies.editionId
        INNER JOIN book_works AS works ON works.id = editions.workId
        WHERE editions.isbn13 = :isbn13
        LIMIT 1
        """,
    )
    suspend fun findOwnedByIsbn(isbn13: String): LibraryBookRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWork(work: BookWorkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEdition(edition: BookEditionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCopy(copy: OwnedCopyEntity)

    @Query(
        """
        UPDATE owned_copies
        SET location = :location, readingStatus = :readingStatus
        WHERE id = :copyId
        """,
    )
    suspend fun updateCopy(
        copyId: String,
        location: String,
        readingStatus: String,
    )
}
