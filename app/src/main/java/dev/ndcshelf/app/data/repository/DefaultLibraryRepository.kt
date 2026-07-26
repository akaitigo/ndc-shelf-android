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
import dev.ndcshelf.app.domain.repository.ShelfMoveDirection
import dev.ndcshelf.app.domain.repository.ShelfMoveResult
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
            return AddBookResult.Duplicate(
                existing.toDomain(),
                dao.countCopiesForEdition(existing.editionId),
            )
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
                        copyLabel = "1冊目",
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
                copyLabel = "1冊目",
            ),
        )
    }

    override suspend fun addAnotherCopy(
        rawIsbn: String,
        copyLabel: String,
    ): AddBookResult {
        val isbn13 = Isbn.normalizeToIsbn13(rawIsbn)
            ?: return AddBookResult.InvalidIsbn(rawIsbn)
        return try {
            database.withTransaction {
                val existing = dao.findOwnedByIsbn(isbn13)
                    ?: return@withTransaction AddBookResult.NotFound(isbn13)
                val count = dao.countCopiesForEdition(existing.editionId)
                val normalizedLabel = copyLabel.trim().ifEmpty { "${count + 1}冊目" }
                if (normalizedLabel.length > MAX_COPY_LABEL_LENGTH || '\u0000' in normalizedLabel) {
                    return@withTransaction AddBookResult.Failure(AddBookFailure.SAVE, isbn13)
                }
                val copyId = idFactory()
                val addedAt = nowMillis()
                dao.insertCopy(
                    OwnedCopyEntity(
                        id = copyId,
                        editionId = existing.editionId,
                        mediaType = MediaType.PHYSICAL.name,
                        location = "未設定",
                        readingStatus = ReadingStatus.UNREAD.name,
                        addedAt = addedAt,
                        copyLabel = normalizedLabel,
                    ),
                )
                AddBookResult.Added(
                    existing.toDomain().copy(
                        copyId = copyId,
                        mediaType = MediaType.PHYSICAL,
                        location = "未設定",
                        readingStatus = ReadingStatus.UNREAD,
                        addedAt = addedAt,
                        locationTierId = null,
                        shelfOrderKey = null,
                        copyLabel = normalizedLabel,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AddBookResult.Failure(AddBookFailure.SAVE, isbn13)
        }
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
                val shelfOrderKey = resolveShelfOrderKey(previous, edit)
                    ?: if (edit.locationTierId == null) null else {
                        return@withTransaction UpdateBookResult.Invalid(
                            listOf(
                                BookEditValidationError(
                                    BookEditField.LOCATION,
                                    "選択した挿入位置が見つかりません",
                                ),
                            ),
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
                    shelfOrderKey = shelfOrderKey,
                    copyLabel = edit.copyLabel,
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
                        shelfOrderKey = shelfOrderKey,
                        copyLabel = edit.copyLabel,
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
                    shelfOrderKey = previous.shelfOrderKey,
                    copyLabel = previous.copyLabel,
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

    override suspend fun moveBookWithinTier(
        copyId: String,
        direction: ShelfMoveDirection,
    ): ShelfMoveResult = try {
        database.withTransaction {
            val copy = dao.findCopyById(copyId) ?: return@withTransaction ShelfMoveResult.NotFound
            val tierId = copy.tierId ?: return@withTransaction ShelfMoveResult.NotFound
            ensureTierKeys(tierId)
            val ordered = database.locationDao().getOrderedCopies(tierId)
            val index = ordered.indexOfFirst { it.id == copyId }
            if (index < 0) return@withTransaction ShelfMoveResult.NotFound
            val target = index + if (direction == ShelfMoveDirection.LEFT) -1 else 1
            if (target !in ordered.indices) return@withTransaction ShelfMoveResult.Boundary
            val withoutCurrent = ordered.filterNot { it.id == copyId }
            val insertionIndex = target.coerceIn(0, withoutCurrent.size)
            val left = withoutCurrent.getOrNull(insertionIndex - 1)?.shelfOrderKey
            val right = withoutCurrent.getOrNull(insertionIndex)?.shelfOrderKey
            check(database.locationDao().updateCopyOrder(
                copyId,
                FractionalOrderKey.between(left, right, copyId),
            ) == 1)
            ShelfMoveResult.Moved
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ShelfMoveResult.Failure
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

    private suspend fun resolveShelfOrderKey(
        previous: LibraryBook,
        edit: dev.ndcshelf.app.domain.model.ValidatedBookEdit,
    ): String? {
        val tierId = edit.locationTierId ?: return null
        if (tierId == previous.locationTierId && !edit.locationPositionSpecified) {
            return previous.shelfOrderKey ?: appendOrderKey(tierId, previous.copyId)
        }
        ensureTierKeys(tierId)
        val copies = database.locationDao().getOrderedCopies(tierId, previous.copyId)
        val insertionIndex = when {
            edit.locationInsertAtStart -> 0
            edit.locationInsertAfterCopyId != null -> {
                val index = copies.indexOfFirst { it.id == edit.locationInsertAfterCopyId }
                if (index < 0) return null
                index + 1
            }
            else -> copies.size
        }
        return FractionalOrderKey.between(
            copies.getOrNull(insertionIndex - 1)?.shelfOrderKey,
            copies.getOrNull(insertionIndex)?.shelfOrderKey,
            previous.copyId,
        )
    }

    private suspend fun appendOrderKey(tierId: String, copyId: String): String {
        ensureTierKeys(tierId)
        val last = database.locationDao().getOrderedCopies(tierId, copyId).lastOrNull()
        return FractionalOrderKey.between(last?.shelfOrderKey, null, copyId)
    }

    private suspend fun ensureTierKeys(tierId: String) {
        val copies = database.locationDao().getOrderedCopies(tierId)
        if (copies.none { it.shelfOrderKey == null }) return
        var previous: String? = null
        copies.forEach { copy ->
            val key = FractionalOrderKey.between(previous, null, copy.id)
            check(database.locationDao().updateCopyOrder(copy.id, key) == 1)
            previous = key
        }
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
    shelfOrderKey = shelfOrderKey,
    copyLabel = copyLabel,
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
    shelfOrderKey = shelfOrderKey,
    copyLabel = copyLabel,
)

private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default

private const val MAX_COPY_LABEL_LENGTH = 100
