package dev.ndcshelf.app.data.repository

import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.LibraryBookRow
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.NdlBookMetadataService
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.scanner.Isbn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class DefaultLibraryRepository(
    private val database: AppDatabase,
    private val metadataService: NdlBookMetadataService,
) : LibraryRepository {
    private val dao = database.libraryDao()

    override fun observeLibrary(): Flow<List<LibraryBook>> =
        dao.observeLibrary().map { rows -> rows.map(LibraryBookRow::toDomain) }

    override suspend fun addFromIsbn(rawIsbn: String): AddBookResult {
        val isbn13 = Isbn.normalizeToIsbn13(rawIsbn)
            ?: return AddBookResult.InvalidIsbn(rawIsbn)

        dao.findOwnedByIsbn(isbn13)?.let { existing ->
            return AddBookResult.Duplicate(existing.toDomain())
        }

        val metadata = try {
            metadataService.findByIsbn(isbn13)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return AddBookResult.Failure(
                error.message ?: "書誌情報の取得に失敗しました",
            )
        } ?: return AddBookResult.NotFound(isbn13)

        val workId = UUID.randomUUID().toString()
        val editionId = UUID.randomUUID().toString()
        val copyId = UUID.randomUUID().toString()
        val author = metadata.authors.joinToString("・").ifBlank { "著者不明" }
        val source = if (metadata.ndcCode == null) {
            ClassificationSource.UNKNOWN
        } else {
            ClassificationSource.NDL
        }
        val addedAt = System.currentTimeMillis()

        database.withTransaction {
            dao.insertWork(
                BookWorkEntity(
                    id = workId,
                    title = metadata.title,
                    primaryAuthor = author,
                ),
            )
            dao.insertEdition(
                BookEditionEntity(
                    id = editionId,
                    workId = workId,
                    isbn13 = isbn13,
                    publisher = metadata.publisher,
                    publishedYear = metadata.publishedYear,
                    coverUrl = metadata.coverUrl,
                    ndcCode = metadata.ndcCode,
                    ndcEdition = metadata.ndcEdition,
                    classificationSource = source.name,
                ),
            )
            dao.insertCopy(
                OwnedCopyEntity(
                    id = copyId,
                    editionId = editionId,
                    mediaType = MediaType.PHYSICAL.name,
                    location = "未設定",
                    readingStatus = ReadingStatus.UNREAD.name,
                    addedAt = addedAt,
                ),
            )
        }

        return AddBookResult.Added(
            LibraryBook(
                copyId = copyId,
                workId = workId,
                editionId = editionId,
                title = metadata.title,
                primaryAuthor = author,
                isbn13 = isbn13,
                publisher = metadata.publisher,
                publishedYear = metadata.publishedYear,
                coverUrl = metadata.coverUrl,
                ndcCode = metadata.ndcCode,
                ndcEdition = metadata.ndcEdition,
                classificationSource = source,
                mediaType = MediaType.PHYSICAL,
                location = "未設定",
                readingStatus = ReadingStatus.UNREAD,
                addedAt = addedAt,
            ),
        )
    }

    override suspend fun updateCopy(
        copyId: String,
        location: String,
        readingStatus: ReadingStatus,
    ) {
        dao.updateCopy(
            copyId = copyId,
            location = location.trim().ifBlank { "未設定" },
            readingStatus = readingStatus.name,
        )
    }
}

private fun LibraryBookRow.toDomain(): LibraryBook = LibraryBook(
    copyId = copyId,
    workId = workId,
    editionId = editionId,
    title = title,
    primaryAuthor = primaryAuthor,
    isbn13 = isbn13,
    publisher = publisher,
    publishedYear = publishedYear,
    coverUrl = coverUrl,
    ndcCode = ndcCode,
    ndcEdition = ndcEdition,
    classificationSource = classificationSource.toEnumOrDefault(
        ClassificationSource.UNKNOWN,
    ),
    mediaType = mediaType.toEnumOrDefault(MediaType.PHYSICAL),
    location = location,
    readingStatus = readingStatus.toEnumOrDefault(ReadingStatus.UNREAD),
    addedAt = addedAt,
)

private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
