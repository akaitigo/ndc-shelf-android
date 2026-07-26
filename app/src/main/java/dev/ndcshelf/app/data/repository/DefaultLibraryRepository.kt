package dev.ndcshelf.app.data.repository

import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.LibraryBookRow
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.NdlBookMetadataService
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportCommitter
import dev.ndcshelf.app.domain.importer.LibraryImportPlanner
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditValidationResult
import dev.ndcshelf.app.domain.model.BookEditValidator
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.UpdateBookResult
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
    private val bookEditValidator = BookEditValidator()
    private val importPlanner = LibraryImportPlanner()
    private val importCommitter = LibraryImportCommitter(
        readCurrentBooks = { dao.getLibrary().map(LibraryBookRow::toDomain) },
        runInTransaction = { block -> database.withTransaction { block() } },
        writeBooks = ::writeImportedBooks,
    )

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

    override suspend fun updateBook(copyId: String, draft: BookEditDraft): UpdateBookResult {
        val edit = when (val validation = bookEditValidator.validate(draft)) {
            is BookEditValidationResult.Invalid -> return UpdateBookResult.Invalid(validation.errors)
            is BookEditValidationResult.Valid -> validation.edit
        }
        return try {
            database.withTransaction {
                val previous = dao.findOwnedByCopyId(copyId)?.toDomain()
                    ?: return@withTransaction UpdateBookResult.NotFound
                val source = if (
                    edit.ndcCode != previous.ndcCode || edit.ndcEdition != previous.ndcEdition
                ) {
                    ClassificationSource.MANUAL
                } else {
                    previous.classificationSource
                }
                dao.updateWork(
                    workId = previous.workId,
                    title = edit.title,
                    primaryAuthor = edit.primaryAuthor,
                )
                dao.updateEdition(
                    editionId = previous.editionId,
                    publisher = edit.publisher,
                    publishedYear = edit.publishedYear,
                    ndcCode = edit.ndcCode,
                    ndcEdition = edit.ndcEdition,
                    classificationSource = source.name,
                )
                dao.updateCopy(
                    copyId = previous.copyId,
                    location = edit.location,
                    readingStatus = edit.readingStatus.name,
                )
                UpdateBookResult.Updated(
                    previous = previous,
                    current = previous.copy(
                        title = edit.title,
                        primaryAuthor = edit.primaryAuthor,
                        publisher = edit.publisher,
                        publishedYear = edit.publishedYear,
                        ndcCode = edit.ndcCode,
                        ndcEdition = edit.ndcEdition,
                        classificationSource = source,
                        location = edit.location,
                        readingStatus = edit.readingStatus,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            UpdateBookResult.Failure
        }
    }

    override suspend fun restoreBook(
        previous: LibraryBook,
        expectedCurrent: LibraryBook,
    ): Boolean {
        if (previous.copyId != expectedCurrent.copyId ||
            previous.workId != expectedCurrent.workId ||
            previous.editionId != expectedCurrent.editionId
        ) {
            return false
        }
        return try {
            database.withTransaction {
                val current = dao.findOwnedByCopyId(previous.copyId)?.toDomain()
                    ?: return@withTransaction false
                if (current != expectedCurrent) return@withTransaction false
                dao.updateWork(previous.workId, previous.title, previous.primaryAuthor)
                dao.updateEdition(
                    editionId = previous.editionId,
                    publisher = previous.publisher,
                    publishedYear = previous.publishedYear,
                    ndcCode = previous.ndcCode,
                    ndcEdition = previous.ndcEdition,
                    classificationSource = previous.classificationSource.name,
                )
                dao.updateCopy(
                    copyId = previous.copyId,
                    location = previous.location,
                    readingStatus = previous.readingStatus.name,
                )
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult = importPlanner.preview(
        batch = batch,
        existingBooks = dao.getLibrary().map(LibraryBookRow::toDomain),
        conflictPolicy = conflictPolicy,
    )

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult =
        importCommitter.commit(preview)

    private suspend fun writeImportedBooks(books: List<LibraryBook>) {
        dao.upsertWorks(
            books.distinctBy(LibraryBook::workId).map { book ->
                BookWorkEntity(
                    id = book.workId,
                    title = book.title,
                    primaryAuthor = book.primaryAuthor,
                )
            },
        )
        dao.upsertEditions(
            books.distinctBy(LibraryBook::editionId).map { book ->
                BookEditionEntity(
                    id = book.editionId,
                    workId = book.workId,
                    isbn13 = book.isbn13,
                    publisher = book.publisher,
                    publishedYear = book.publishedYear,
                    coverUrl = book.coverUrl,
                    ndcCode = book.ndcCode,
                    ndcEdition = book.ndcEdition,
                    classificationSource = book.classificationSource.name,
                )
            },
        )
        dao.upsertCopies(
            books.map { book ->
                OwnedCopyEntity(
                    id = book.copyId,
                    editionId = book.editionId,
                    mediaType = book.mediaType.name,
                    location = book.location,
                    readingStatus = book.readingStatus.name,
                    addedAt = book.addedAt,
                )
            },
        )
    }
}

internal fun LibraryBookRow.toDomain(): LibraryBook = LibraryBook(
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
