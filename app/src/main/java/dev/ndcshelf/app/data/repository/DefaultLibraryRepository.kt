package dev.ndcshelf.app.data.repository

import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.LibraryBookRow
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.BookMetadataFailure
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportCommitter
import dev.ndcshelf.app.domain.importer.LibraryImportPlanner
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditField
import dev.ndcshelf.app.domain.model.BookEditValidationError
import dev.ndcshelf.app.domain.model.BookEditValidationResult
import dev.ndcshelf.app.domain.model.BookEditValidator
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.scanner.Isbn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class DefaultLibraryRepository(
    private val database: AppDatabase,
    private val metadataService: BookMetadataService,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
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

        val lookup = try {
            metadataService.findByIsbn(isbn13)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return AddBookResult.Failure(AddBookFailure.NETWORK, isbn13)
        }
        val metadata = when (lookup) {
            is BookMetadataLookupResult.Found -> lookup.metadata
            BookMetadataLookupResult.NotFound -> return AddBookResult.NotFound(isbn13)
            is BookMetadataLookupResult.Failure -> return AddBookResult.Failure(
                reason = lookup.reason.toAddBookFailure(),
                isbn13 = isbn13,
            )
        }

        val workId = idFactory()
        val editionId = idFactory()
        val copyId = idFactory()
        val author = metadata.authors.joinToString("・").ifBlank { "著者不明" }
        val source = if (metadata.ndcCode == null) {
            ClassificationSource.UNKNOWN
        } else {
            ClassificationSource.NDL
        }
        val addedAt = nowMillis()

        try {
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
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return AddBookResult.Failure(AddBookFailure.SAVE, isbn13)
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
                if (edit.locationTierId != null &&
                    database.locationDao().findTier(edit.locationTierId) == null
                ) {
                    return@withTransaction UpdateBookResult.Invalid(
                        listOf(BookEditValidationError(BookEditField.LOCATION, "選択した場所が見つかりません")),
                    )
                }
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
                    tierId = edit.locationTierId,
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
                        locationTierId = edit.locationTierId,
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
                    tierId = previous.locationTierId,
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

    override suspend fun deleteBook(copyId: String): DeleteBookResult = try {
        database.withTransaction {
            val book = dao.findOwnedByCopyId(copyId)?.toDomain()
                ?: return@withTransaction DeleteBookResult.NotFound
            check(dao.deleteCopyById(copyId) == 1)
            if (dao.countCopiesForEdition(book.editionId) == 0) {
                check(dao.deleteEditionById(book.editionId) == 1)
            }
            if (dao.countEditionsForWork(book.workId) == 0) {
                check(dao.deleteWorkById(book.workId) == 1)
            }
            DeleteBookResult.Deleted(book)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        DeleteBookResult.Failure
    }

    override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult = try {
        database.withTransaction {
            val expectedWork = book.toWorkEntity()
            val expectedEdition = book.toEditionEntity()
            val expectedCopy = book.toCopyEntity()
            if (dao.findCopyById(book.copyId) != null) {
                return@withTransaction RestoreDeletedBookResult.Conflict
            }
            val existingWork = dao.findWorkById(book.workId)
            if (existingWork != null && existingWork != expectedWork) {
                return@withTransaction RestoreDeletedBookResult.Conflict
            }
            val existingEdition = dao.findEditionById(book.editionId)
            val isbnEdition = dao.findEditionByIsbn(book.isbn13)
            if (existingEdition != null && existingEdition != expectedEdition) {
                return@withTransaction RestoreDeletedBookResult.Conflict
            }
            if (isbnEdition != null && isbnEdition.id != book.editionId) {
                return@withTransaction RestoreDeletedBookResult.Conflict
            }
            if (existingWork == null) dao.insertWork(expectedWork)
            if (existingEdition == null) dao.insertEdition(expectedEdition)
            dao.insertCopy(expectedCopy)
            RestoreDeletedBookResult.Restored
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        RestoreDeletedBookResult.Failure
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
            books.distinctBy(LibraryBook::workId).map(LibraryBook::toWorkEntity),
        )
        dao.upsertEditions(
            books.distinctBy(LibraryBook::editionId).map(LibraryBook::toEditionEntity),
        )
        dao.upsertCopies(books.map(LibraryBook::toCopyEntity))
    }
}

private fun BookMetadataFailure.toAddBookFailure(): AddBookFailure = when (this) {
    BookMetadataFailure.OFFLINE -> AddBookFailure.OFFLINE
    BookMetadataFailure.TIMEOUT -> AddBookFailure.TIMEOUT
    BookMetadataFailure.RATE_LIMITED -> AddBookFailure.RATE_LIMITED
    BookMetadataFailure.SERVER -> AddBookFailure.SERVICE_UNAVAILABLE
    BookMetadataFailure.NETWORK -> AddBookFailure.NETWORK
    BookMetadataFailure.CLIENT -> AddBookFailure.REQUEST_REJECTED
    BookMetadataFailure.PARSE -> AddBookFailure.INVALID_RESPONSE
}

private fun LibraryBook.toWorkEntity() = BookWorkEntity(
    id = workId,
    title = title,
    primaryAuthor = primaryAuthor,
)

private fun LibraryBook.toEditionEntity() = BookEditionEntity(
    id = editionId,
    workId = workId,
    isbn13 = isbn13,
    publisher = publisher,
    publishedYear = publishedYear,
    coverUrl = coverUrl,
    ndcCode = ndcCode,
    ndcEdition = ndcEdition,
    classificationSource = classificationSource.name,
)

private fun LibraryBook.toCopyEntity() = OwnedCopyEntity(
    id = copyId,
    editionId = editionId,
    mediaType = mediaType.name,
    location = location,
    readingStatus = readingStatus.name,
    addedAt = addedAt,
    tierId = locationTierId,
)

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
    locationTierId = locationTierId,
)

private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
