package dev.ndcshelf.app.data.repository

import androidx.room.withTransaction
import dev.ndcshelf.app.R
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.LibraryBookRow
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.ScanAttemptEntity
import dev.ndcshelf.app.data.local.ScanSessionAttemptRow
import dev.ndcshelf.app.data.local.ScanSessionEntity
import dev.ndcshelf.app.data.local.TagAssignmentEntity
import dev.ndcshelf.app.data.local.TagEntity
import dev.ndcshelf.app.data.local.WishlistBookRow
import dev.ndcshelf.app.data.local.WishlistItemEntity
import dev.ndcshelf.app.data.local.toSQLiteQuery
import dev.ndcshelf.app.data.remote.BookMetadataFailure
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.data.sync.syncDelete
import dev.ndcshelf.app.data.sync.toSyncUpsert
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportCommitter
import dev.ndcshelf.app.domain.importer.LibraryImportPlanner
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditField
import dev.ndcshelf.app.domain.model.BookEditValidationError
import dev.ndcshelf.app.domain.model.BookEditValidationResult
import dev.ndcshelf.app.domain.model.BookEditValidator
import dev.ndcshelf.app.domain.model.BookstoreBook
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibraryDefaults
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.ManualBookDraft
import dev.ndcshelf.app.domain.model.ManualBookValidationResult
import dev.ndcshelf.app.domain.model.ManualBookValidator
import dev.ndcshelf.app.domain.model.ManualReconciliationPreview
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.NdlReconciliationCandidate
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.ReconciliationEditionSnapshot
import dev.ndcshelf.app.domain.model.ScanAttempt
import dev.ndcshelf.app.domain.model.ScanAttemptOutcome
import dev.ndcshelf.app.domain.model.ScanSession
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagColorRole
import dev.ndcshelf.app.domain.model.TagNameRules
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.BookstoreChangeResult
import dev.ndcshelf.app.domain.repository.BookstoreLookupResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.ManualBookResult
import dev.ndcshelf.app.domain.repository.ManualReconciliationApplyResult
import dev.ndcshelf.app.domain.repository.ManualReconciliationLookupResult
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.ScanUndoResult
import dev.ndcshelf.app.domain.repository.ShelfMoveDirection
import dev.ndcshelf.app.domain.repository.ShelfMoveResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.domain.sync.SyncMutation
import dev.ndcshelf.app.domain.sync.SyncMutationJournal
import dev.ndcshelf.app.domain.text.UiMessage
import dev.ndcshelf.app.scanner.Isbn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID

class DefaultLibraryRepository(
    private val database: AppDatabase,
    private val metadataService: BookMetadataService,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val syncJournal: SyncMutationJournal = SyncMutationJournal.Disabled,
) : LibraryRepository {
    private val dao = database.libraryDao()
    private val bookEditValidator = BookEditValidator()
    private val manualBookValidator = ManualBookValidator(nowMillis)
    private val importPlanner = LibraryImportPlanner()
    private val importCommitter =
        LibraryImportCommitter(
            readCurrentBooks = { dao.getLibrary().map(LibraryBookRow::toDomain) },
            runInTransaction = { block -> database.withTransaction { block() } },
            writeBooks = ::writeImportedBooks,
        )

    override fun observeLibrary(): Flow<List<LibraryBook>> = dao.observeLibrary().map { rows -> rows.map(LibraryBookRow::toDomain) }

    override fun observeLibrary(criteria: LibrarySearchCriteria): Flow<List<LibraryBook>> =
        dao.observeLibrarySearch(criteria.toSQLiteQuery()).map { rows ->
            rows.map(LibraryBookRow::toDomain)
        }

    override fun observeLibraryStats(): Flow<LibraryStats> =
        dao.observeLibraryStats().map { row ->
            LibraryStats(row.totalCount, row.classifiedCount, row.readingCount)
        }

    override suspend fun getLibrarySnapshot(): List<LibraryBook> = dao.getLibrary().map(LibraryBookRow::toDomain)

    override fun observeWishlist(): Flow<List<BookstoreBook>> = dao.observeWishlist().map { rows -> rows.map(WishlistBookRow::toDomain) }

    override fun observeScanSessions(): Flow<List<ScanSession>> =
        dao.observeRecentScanSessions(RECENT_SCAN_SESSION_LIMIT).map(::toScanSessions)

    override suspend fun startScanSession(): String? =
        try {
            database.withTransaction {
                dao.findActiveScanSession()?.id ?: idFactory().also { sessionId ->
                    dao.insertScanSession(
                        ScanSessionEntity(
                            id = sessionId,
                            startedAt = nowMillis(),
                            endedAt = null,
                        ),
                    )
                    dao.pruneScanSessions(RECENT_SCAN_SESSION_LIMIT)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    override suspend fun activeScanSessionId(): String? =
        try {
            dao.findActiveScanSession()?.id
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    override suspend fun finishScanSession(sessionId: String): Boolean =
        try {
            database.withTransaction {
                dao.finishScanSession(sessionId, nowMillis()) == 1
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    override suspend fun recordScanAttempt(
        sessionId: String,
        rawIsbn: String,
        result: AddBookResult,
    ): Boolean =
        try {
            database.withTransaction {
                val active = dao.findActiveScanSession()
                if (active?.id != sessionId) return@withTransaction false
                val addedResult = result as? AddBookResult.Added
                val addedBook =
                    if (addedResult != null) {
                        dao.findOwnedByCopyId(addedResult.book.copyId)?.toDomain()
                            ?: return@withTransaction false
                    } else {
                        null
                    }
                if (addedBook != null && addedBook.snapshotHash() != addedResult?.book?.snapshotHash()) {
                    return@withTransaction false
                }
                val outcome =
                    when (result) {
                        is AddBookResult.Added -> ScanAttemptOutcome.ADDED
                        is AddBookResult.Duplicate -> ScanAttemptOutcome.DUPLICATE
                        is AddBookResult.InvalidIsbn -> ScanAttemptOutcome.INVALID
                        is AddBookResult.NotFound -> ScanAttemptOutcome.NOT_FOUND
                        is AddBookResult.Failure -> ScanAttemptOutcome.FAILURE
                    }
                val isbn =
                    when (result) {
                        is AddBookResult.Added -> {
                            result.book.isbn13.orEmpty()
                        }

                        is AddBookResult.Duplicate -> {
                            result.book.isbn13.orEmpty()
                        }

                        is AddBookResult.InvalidIsbn -> {
                            result.rawValue.trim().take(MAX_RECORDED_ISBN_LENGTH)
                        }

                        is AddBookResult.NotFound -> {
                            result.isbn13
                        }

                        is AddBookResult.Failure -> {
                            result.isbn13
                                ?: rawIsbn.trim().take(MAX_RECORDED_ISBN_LENGTH)
                        }
                    }
                dao.insertScanAttempt(
                    ScanAttemptEntity(
                        id = idFactory(),
                        sessionId = sessionId,
                        isbn = isbn,
                        outcome = outcome.name,
                        copyId = addedBook?.copyId,
                        copySnapshot = addedBook?.snapshotHash(),
                        attemptedAt = nowMillis(),
                        undoneAt = null,
                    ),
                )
                true
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    override suspend fun undoScanAttempt(attemptId: String): ScanUndoResult =
        try {
            database.withTransaction {
                val attempt =
                    dao.findScanAttempt(attemptId)
                        ?: return@withTransaction ScanUndoResult.NotFound
                undoAttempts(listOf(attempt))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ScanUndoResult.Failure
        }

    override suspend fun undoScanSession(sessionId: String): ScanUndoResult =
        try {
            database.withTransaction {
                val attempts =
                    dao
                        .findScanAttemptsBySession(sessionId)
                        .filter { it.outcome == ScanAttemptOutcome.ADDED.name && it.undoneAt == null }
                if (attempts.isEmpty()) return@withTransaction ScanUndoResult.NotFound
                undoAttempts(attempts)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ScanUndoResult.Failure
        }

    private suspend fun undoAttempts(attempts: List<ScanAttemptEntity>): ScanUndoResult {
        val books =
            attempts.map { attempt ->
                if (attempt.outcome != ScanAttemptOutcome.ADDED.name ||
                    attempt.undoneAt != null || attempt.copyId == null || attempt.copySnapshot == null
                ) {
                    return ScanUndoResult.Conflict
                }
                val book =
                    dao.findOwnedByCopyId(attempt.copyId)?.toDomain()
                        ?: return ScanUndoResult.Conflict
                if (book.snapshotHash() != attempt.copySnapshot) return ScanUndoResult.Conflict
                attempt to book
            }
        val now = nowMillis()
        val syncMutations = mutableListOf<SyncMutation>()
        books.forEach { (attempt, book) ->
            syncMutations +=
                database.readingSessionDao().findByCopyId(book.copyId).map { session ->
                    syncDelete("readingSession", session.id)
                }
            check(dao.deleteCopyById(book.copyId) == 1)
            syncMutations += syncDelete("ownedCopy", book.copyId)
            check(dao.markScanAttemptUndone(attempt.id, now) == 1)
            if (dao.countCopiesForEdition(book.editionId) == 0 &&
                dao.findWishlistByEditionId(book.editionId) == null
            ) {
                check(dao.deleteEditionById(book.editionId) == 1)
                syncMutations += syncDelete("edition", book.editionId)
                if (dao.countEditionsForWork(book.workId) == 0) {
                    if (database.workGroupDao().findMembershipByWorkId(book.workId) == null) {
                        val seriesMemberships = database.seriesDao().findMembershipsForWork(book.workId)
                        val tagAssignments = database.tagDao().findAssignmentsForWork(book.workId)
                        dao.deleteWorkById(book.workId)
                        syncMutations +=
                            seriesMemberships.map { membership ->
                                syncDelete("seriesMembership", membership.membershipId)
                            }
                        syncMutations +=
                            tagAssignments.map { assignment ->
                                syncDelete("tagAssignment", assignment.id)
                            }
                        syncMutations += syncDelete("work", book.workId)
                    }
                }
            }
        }
        syncJournal.record(syncMutations)
        return ScanUndoResult.Undone(books.size)
    }

    override suspend fun addFromIsbn(rawIsbn: String): AddBookResult {
        val isbn13 =
            Isbn.normalizeToIsbn13(rawIsbn)
                ?: return AddBookResult.InvalidIsbn(rawIsbn)

        dao.findOwnedByIsbn(isbn13)?.let { existing ->
            return AddBookResult.Duplicate(
                existing.toDomain(),
                dao.countCopiesForEdition(existing.editionId),
            )
        }

        findLocalBookstoreBook(isbn13)?.let { local ->
            return when (val changed = changePurchaseState(local, PurchaseTransition.PURCHASED)) {
                is BookstoreChangeResult.Updated -> {
                    val copy =
                        dao.findOwnedByIsbn(isbn13)?.toDomain()
                            ?: return AddBookResult.Failure(AddBookFailure.SAVE, isbn13)
                    AddBookResult.Added(copy)
                }

                BookstoreChangeResult.Conflict,
                BookstoreChangeResult.Failure,
                -> {
                    AddBookResult.Failure(AddBookFailure.SAVE, isbn13)
                }
            }
        }

        val lookup =
            try {
                metadataService.findByIsbn(isbn13)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return AddBookResult.Failure(AddBookFailure.NETWORK, isbn13)
            }
        val metadata =
            when (lookup) {
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
        val source =
            if (metadata.ndcCode == null) {
                ClassificationSource.UNKNOWN
            } else {
                ClassificationSource.NDL
            }
        val addedAt = nowMillis()

        try {
            database.withTransaction {
                val work =
                    BookWorkEntity(
                        id = workId,
                        title = metadata.title,
                        primaryAuthor = author,
                    )
                val edition =
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
                    )
                val copy =
                    OwnedCopyEntity(
                        id = copyId,
                        editionId = editionId,
                        mediaType = MediaType.PHYSICAL.name,
                        location = LibraryDefaults.UNSET_LOCATION,
                        readingStatus = ReadingStatus.UNREAD.name,
                        addedAt = addedAt,
                        copyLabel = "1冊目",
                    )
                dao.insertWork(work)
                dao.insertEdition(edition)
                dao.insertCopy(copy)
                syncJournal.record(listOf(work.toSyncUpsert(), edition.toSyncUpsert(), copy.toSyncUpsert()))
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
                location = LibraryDefaults.UNSET_LOCATION,
                readingStatus = ReadingStatus.UNREAD,
                addedAt = addedAt,
                copyLabel = "1冊目",
            ),
        )
    }

    override suspend fun addManualBook(draft: ManualBookDraft): ManualBookResult {
        val validated =
            when (val result = manualBookValidator.validate(draft)) {
                is ManualBookValidationResult.Invalid -> return ManualBookResult.Invalid(result.errors)
                is ManualBookValidationResult.Valid -> result.book
            }
        return try {
            database.withTransaction {
                validated.isbn13?.let { isbn13 ->
                    dao.findEditionByIsbn(isbn13)?.let { edition ->
                        val work =
                            dao.findWorkById(edition.workId)
                                ?: return@withTransaction ManualBookResult.Failure
                        return@withTransaction ManualBookResult.Duplicate(
                            isbn13 = isbn13,
                            title = work.title,
                            copyCount = dao.countCopiesForEdition(edition.id),
                        )
                    }
                }

                val workId = idFactory()
                val editionId = idFactory()
                val copyId = idFactory()
                val addedAt = nowMillis()
                val classificationSource =
                    if (validated.ndcCode == null) {
                        ClassificationSource.UNKNOWN
                    } else {
                        ClassificationSource.MANUAL
                    }
                val work = BookWorkEntity(workId, validated.title, validated.primaryAuthor)
                val edition =
                    BookEditionEntity(
                        id = editionId,
                        workId = workId,
                        isbn13 = validated.isbn13,
                        publisher = validated.publisher,
                        publishedYear = validated.publishedYear,
                        coverUrl = null,
                        ndcCode = validated.ndcCode,
                        ndcEdition = validated.ndcEdition,
                        classificationSource = classificationSource.name,
                        bibliographicSource = BibliographicSource.MANUAL.name,
                    )
                val copy =
                    OwnedCopyEntity(
                        id = copyId,
                        editionId = editionId,
                        mediaType = validated.mediaType.name,
                        location = LibraryDefaults.UNSET_LOCATION,
                        readingStatus = ReadingStatus.UNREAD.name,
                        addedAt = addedAt,
                        copyLabel = "1冊目",
                    )
                dao.insertWork(work)
                dao.insertEdition(edition)
                dao.insertCopy(copy)
                syncJournal.record(listOf(work.toSyncUpsert(), edition.toSyncUpsert(), copy.toSyncUpsert()))
                ManualBookResult.Added(
                    LibraryBook(
                        copyId = copyId,
                        workId = workId,
                        editionId = editionId,
                        title = validated.title,
                        primaryAuthor = validated.primaryAuthor,
                        isbn13 = validated.isbn13,
                        publisher = validated.publisher,
                        publishedYear = validated.publishedYear,
                        coverUrl = null,
                        ndcCode = validated.ndcCode,
                        ndcEdition = validated.ndcEdition,
                        classificationSource = classificationSource,
                        mediaType = validated.mediaType,
                        location = LibraryDefaults.UNSET_LOCATION,
                        readingStatus = ReadingStatus.UNREAD,
                        addedAt = addedAt,
                        copyLabel = "1冊目",
                        bibliographicSource = BibliographicSource.MANUAL,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ManualBookResult.Failure
        }
    }

    override suspend fun previewManualReconciliation(
        copyId: String,
        rawIsbn: String,
    ): ManualReconciliationLookupResult {
        val isbn13 =
            Isbn.normalizeToIsbn13(rawIsbn)
                ?: return ManualReconciliationLookupResult.InvalidIsbn(rawIsbn)
        val current =
            dao.findOwnedByCopyId(copyId)?.toDomain()
                ?: return ManualReconciliationLookupResult.NotManual
        if (current.bibliographicSource != BibliographicSource.MANUAL) {
            return ManualReconciliationLookupResult.NotManual
        }
        val lookup =
            try {
                metadataService.findByIsbn(isbn13)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return ManualReconciliationLookupResult.Failure(AddBookFailure.NETWORK)
            }
        val metadata =
            when (lookup) {
                is BookMetadataLookupResult.Found -> lookup.metadata

                BookMetadataLookupResult.NotFound -> return ManualReconciliationLookupResult.NotFound(isbn13)

                is BookMetadataLookupResult.Failure -> return ManualReconciliationLookupResult.Failure(
                    lookup.reason.toAddBookFailure(),
                )
            }
        val existing = dao.findEditionByIsbn(isbn13)?.takeIf { it.id != current.editionId }
        val existingWork = existing?.let { dao.findWorkById(it.workId) }
        if (existing != null && existingWork == null) {
            return ManualReconciliationLookupResult.Failure(AddBookFailure.SAVE)
        }
        val candidate =
            if (
                existing == null || existing.bibliographicSource == BibliographicSource.MANUAL.name
            ) {
                val classificationSource =
                    if (metadata.ndcCode == null) {
                        ClassificationSource.UNKNOWN
                    } else {
                        ClassificationSource.NDL
                    }
                NdlReconciliationCandidate(
                    isbn13 = isbn13,
                    title = metadata.title,
                    primaryAuthor = metadata.authors.joinToString("・").ifBlank { "著者不明" },
                    publisher = metadata.publisher,
                    publishedYear = metadata.publishedYear,
                    coverUrl = metadata.coverUrl,
                    ndcCode = metadata.ndcCode,
                    ndcEdition = metadata.ndcEdition,
                    classificationSource = classificationSource,
                )
            } else {
                val work = requireNotNull(existingWork)
                NdlReconciliationCandidate(
                    isbn13 = isbn13,
                    title = work.title,
                    primaryAuthor = work.primaryAuthor,
                    publisher = existing.publisher,
                    publishedYear = existing.publishedYear,
                    coverUrl = existing.coverUrl,
                    ndcCode = existing.ndcCode,
                    ndcEdition = existing.ndcEdition,
                    classificationSource =
                        existing.classificationSource.toEnumOrDefault(
                            ClassificationSource.UNKNOWN,
                        ),
                )
            }
        return ManualReconciliationLookupResult.Ready(
            ManualReconciliationPreview(
                current = current,
                candidate = candidate,
                existingEditionId = existing?.id,
                existingCopyCount = existing?.let { dao.countCopiesForEdition(it.id) } ?: 0,
                currentEditionCopyCount = dao.countCopiesForEdition(current.editionId),
                existingEditionSnapshot =
                    existing?.toReconciliationSnapshot(
                        requireNotNull(existingWork),
                    ),
            ),
        )
    }

    override suspend fun confirmManualReconciliation(preview: ManualReconciliationPreview): ManualReconciliationApplyResult =
        try {
            database.withTransaction {
                val current =
                    dao.findOwnedByCopyId(preview.current.copyId)?.toDomain()
                        ?: return@withTransaction ManualReconciliationApplyResult.Conflict
                if (current != preview.current ||
                    current.bibliographicSource != BibliographicSource.MANUAL
                ) {
                    return@withTransaction ManualReconciliationApplyResult.Conflict
                }
                val isbnEdition =
                    dao
                        .findEditionByIsbn(preview.candidate.isbn13)
                        ?.takeIf { it.id != current.editionId }
                val targetWork = isbnEdition?.let { dao.findWorkById(it.workId) }
                val targetSnapshot =
                    if (isbnEdition != null && targetWork != null) {
                        isbnEdition.toReconciliationSnapshot(targetWork)
                    } else {
                        null
                    }
                val targetCopyCount = isbnEdition?.let { dao.countCopiesForEdition(it.id) } ?: 0
                if (isbnEdition?.id != preview.existingEditionId ||
                    targetSnapshot != preview.existingEditionSnapshot ||
                    targetCopyCount != preview.existingCopyCount ||
                    dao.countCopiesForEdition(current.editionId) != preview.currentEditionCopyCount
                ) {
                    return@withTransaction ManualReconciliationApplyResult.Conflict
                }
                val trackedWorkIds = setOfNotNull(current.workId, isbnEdition?.workId)
                val trackedEditionIds = setOfNotNull(current.editionId, isbnEdition?.id)
                val trackedCopyIds =
                    dao
                        .getAllCopies()
                        .filter { it.editionId in trackedEditionIds }
                        .mapTo(linkedSetOf(), OwnedCopyEntity::id)
                val groupsBefore = database.workGroupDao().getAllGroups().associateBy { it.id }
                val membershipsBefore = database.workGroupDao().getAllMemberships().associateBy { it.id }
                val seriesMembershipsBefore = database.seriesDao().getAllMemberships().associateBy { it.id }
                val tagAssignmentsBefore =
                    database.tagDao().getAllAssignments().associateBy { it.id }
                val wishlistBefore =
                    trackedEditionIds.associateWith { editionId ->
                        dao.findWishlistByEditionId(editionId)
                    }
                val targetId = isbnEdition?.id
                if (targetId != null) {
                    val target = isbnEdition
                    if (target.bibliographicSource == BibliographicSource.MANUAL.name) {
                        dao.updateWork(
                            target.workId,
                            preview.candidate.title,
                            preview.candidate.primaryAuthor,
                        )
                        check(
                            dao.reconcileManualEdition(
                                editionId = target.id,
                                isbn13 = preview.candidate.isbn13,
                                publisher = preview.candidate.publisher,
                                publishedYear = preview.candidate.publishedYear,
                                coverUrl = preview.candidate.coverUrl,
                                ndcCode = preview.candidate.ndcCode,
                                ndcEdition = preview.candidate.ndcEdition,
                                classificationSource = preview.candidate.classificationSource.name,
                            ) == 1,
                        )
                    }
                    check(dao.moveCopiesToEdition(current.editionId, targetId) > 0)
                    dao.deleteWishlistByEditionId(targetId)
                    check(dao.deleteEditionById(current.editionId) == 1)
                    if (dao.countEditionsForWork(current.workId) == 0) {
                        dao.deleteWorkById(current.workId)
                        database.workGroupDao().deleteUndersizedGroups()
                    }
                } else {
                    dao.updateWork(
                        current.workId,
                        preview.candidate.title,
                        preview.candidate.primaryAuthor,
                    )
                    check(
                        dao.reconcileManualEdition(
                            editionId = current.editionId,
                            isbn13 = preview.candidate.isbn13,
                            publisher = preview.candidate.publisher,
                            publishedYear = preview.candidate.publishedYear,
                            coverUrl = preview.candidate.coverUrl,
                            ndcCode = preview.candidate.ndcCode,
                            ndcEdition = preview.candidate.ndcEdition,
                            classificationSource = preview.candidate.classificationSource.name,
                        ) == 1,
                    )
                }
                val workMutations =
                    trackedWorkIds.map { workId ->
                        dao.findWorkById(workId)?.toSyncUpsert() ?: syncDelete("work", workId)
                    }
                val editionMutations =
                    trackedEditionIds.map { editionId ->
                        dao.findEditionById(editionId)?.toSyncUpsert() ?: syncDelete("edition", editionId)
                    }
                val copyMutations =
                    trackedCopyIds.map { copyId ->
                        dao.findCopyById(copyId)?.toSyncUpsert() ?: syncDelete("ownedCopy", copyId)
                    }
                val wishlistMutations =
                    wishlistBefore.mapNotNull { (editionId, before) ->
                        if (before != null && dao.findWishlistByEditionId(editionId) == null) {
                            syncDelete("wishlistItem", editionId)
                        } else {
                            null
                        }
                    }
                val groupsAfter = database.workGroupDao().getAllGroups().associateBy { it.id }
                val membershipsAfter = database.workGroupDao().getAllMemberships().associateBy { it.id }
                val seriesMembershipsAfter = database.seriesDao().getAllMemberships().associateBy { it.id }
                val groupMutations =
                    groupsBefore.keys.union(groupsAfter.keys).map { id ->
                        groupsAfter[id]?.toSyncUpsert() ?: syncDelete("workGroup", id)
                    }
                val membershipMutations =
                    membershipsBefore.keys.union(membershipsAfter.keys).map { id ->
                        membershipsAfter[id]?.toSyncUpsert() ?: syncDelete("workGroupMembership", id)
                    }
                val seriesMembershipMutations =
                    seriesMembershipsBefore.keys
                        .union(seriesMembershipsAfter.keys)
                        .map { id ->
                            seriesMembershipsAfter[id]?.toSyncUpsert() ?: syncDelete("seriesMembership", id)
                        }
                val tagAssignmentsAfter =
                    database.tagDao().getAllAssignments().associateBy { it.id }
                val tagAssignmentMutations =
                    tagAssignmentsBefore.keys
                        .union(tagAssignmentsAfter.keys)
                        .mapNotNull { id ->
                            when {
                                tagAssignmentsAfter[id] == null -> {
                                    syncDelete("tagAssignment", id)
                                }

                                tagAssignmentsBefore[id] != tagAssignmentsAfter[id] -> {
                                    tagAssignmentsAfter[id]?.toSyncUpsert()
                                }

                                else -> {
                                    null
                                }
                            }
                        }
                syncJournal.record(
                    copyMutations + membershipMutations + seriesMembershipMutations +
                        tagAssignmentMutations + editionMutations + groupMutations +
                        workMutations + wishlistMutations,
                )
                ManualReconciliationApplyResult.Applied
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ManualReconciliationApplyResult.Failure
        }

    override suspend fun addAnotherCopy(
        rawIsbn: String,
        copyLabel: String,
    ): AddBookResult {
        val isbn13 =
            Isbn.normalizeToIsbn13(rawIsbn)
                ?: return AddBookResult.InvalidIsbn(rawIsbn)
        return try {
            database.withTransaction {
                val existing =
                    dao.findOwnedByIsbn(isbn13)
                        ?: return@withTransaction AddBookResult.NotFound(isbn13)
                val count = dao.countCopiesForEdition(existing.editionId)
                val normalizedLabel = copyLabel.trim().ifEmpty { "${count + 1}冊目" }
                if (normalizedLabel.length > MAX_COPY_LABEL_LENGTH || '\u0000' in normalizedLabel) {
                    return@withTransaction AddBookResult.Failure(AddBookFailure.SAVE, isbn13)
                }
                val copyId = idFactory()
                val addedAt = nowMillis()
                val copy =
                    OwnedCopyEntity(
                        id = copyId,
                        editionId = existing.editionId,
                        mediaType = MediaType.PHYSICAL.name,
                        location = LibraryDefaults.UNSET_LOCATION,
                        readingStatus = ReadingStatus.UNREAD.name,
                        addedAt = addedAt,
                        copyLabel = normalizedLabel,
                    )
                dao.insertCopy(copy)
                syncJournal.record(listOf(copy.toSyncUpsert()))
                AddBookResult.Added(
                    existing.toDomain().copy(
                        copyId = copyId,
                        mediaType = MediaType.PHYSICAL,
                        location = LibraryDefaults.UNSET_LOCATION,
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

    override suspend fun lookupBookstore(rawIsbn: String): BookstoreLookupResult {
        val isbn13 =
            Isbn.normalizeToIsbn13(rawIsbn)
                ?: return BookstoreLookupResult.InvalidIsbn(rawIsbn)
        findLocalBookstoreBook(isbn13)?.let { return BookstoreLookupResult.Found(it) }

        val lookup =
            try {
                metadataService.findByIsbn(isbn13)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return BookstoreLookupResult.Failure(AddBookFailure.NETWORK, isbn13)
            }
        return when (lookup) {
            is BookMetadataLookupResult.Found -> {
                val metadata = lookup.metadata
                BookstoreLookupResult.Found(
                    BookstoreBook(
                        workId = idFactory(),
                        editionId = idFactory(),
                        title = metadata.title,
                        primaryAuthor = metadata.authors.joinToString("・").ifBlank { "著者不明" },
                        isbn13 = isbn13,
                        publisher = metadata.publisher,
                        publishedYear = metadata.publishedYear,
                        coverUrl = metadata.coverUrl,
                        ndcCode = metadata.ndcCode,
                        ndcEdition = metadata.ndcEdition,
                        classificationSource =
                            if (metadata.ndcCode == null) {
                                ClassificationSource.UNKNOWN
                            } else {
                                ClassificationSource.NDL
                            },
                        purchaseStatus = null,
                        ownedCopyCount = 0,
                    ),
                )
            }

            BookMetadataLookupResult.NotFound -> {
                BookstoreLookupResult.NotFound(isbn13)
            }

            is BookMetadataLookupResult.Failure -> {
                BookstoreLookupResult.Failure(
                    lookup.reason.toAddBookFailure(),
                    isbn13,
                )
            }
        }
    }

    override suspend fun changePurchaseState(
        book: BookstoreBook,
        transition: PurchaseTransition,
    ): BookstoreChangeResult =
        try {
            database.withTransaction {
                val existingEdition = dao.findEditionByIsbn(book.isbn13)
                val syncMutations = mutableListOf<SyncMutation>()
                val editionId: String
                val workId: String
                if (existingEdition == null) {
                    if (book.purchaseStatus != null || book.ownedCopyCount != 0) {
                        return@withTransaction BookstoreChangeResult.Conflict
                    }
                    val work = book.toWorkEntity()
                    val edition = book.toEditionEntity()
                    dao.insertWork(work)
                    dao.insertEdition(edition)
                    syncMutations += work.toSyncUpsert()
                    syncMutations += edition.toSyncUpsert()
                    editionId = book.editionId
                    workId = book.workId
                } else {
                    editionId = existingEdition.id
                    workId = existingEdition.workId
                }
                val previous = dao.findWishlistByEditionId(editionId)
                val persistedStatus =
                    previous?.status?.let { rawStatus ->
                        PurchaseStatus.entries.firstOrNull { it.name == rawStatus }
                            ?: return@withTransaction BookstoreChangeResult.Conflict
                    }
                val currentCopyCount = dao.countCopiesForEdition(editionId)
                if (persistedStatus != book.purchaseStatus ||
                    previous?.createdAt != book.createdAt ||
                    previous?.updatedAt != book.updatedAt ||
                    currentCopyCount != book.ownedCopyCount
                ) {
                    return@withTransaction BookstoreChangeResult.Conflict
                }
                val now = nowMillis()
                val status =
                    when (transition) {
                        PurchaseTransition.WANTED -> PurchaseStatus.WANTED
                        PurchaseTransition.RESERVED -> PurchaseStatus.RESERVED
                        PurchaseTransition.PURCHASED -> null
                    }
                if (status != null) {
                    val updatedAt = maxOf(now, (previous?.updatedAt ?: -1L) + 1L)
                    val wishlist =
                        WishlistItemEntity(
                            editionId = editionId,
                            status = status.name,
                            createdAt = previous?.createdAt ?: now,
                            updatedAt = updatedAt,
                        )
                    dao.upsertWishlistItem(wishlist)
                    syncMutations += wishlist.toSyncUpsert()
                } else {
                    val copy =
                        OwnedCopyEntity(
                            id = idFactory(),
                            editionId = editionId,
                            mediaType = MediaType.PHYSICAL.name,
                            location = LibraryDefaults.UNSET_LOCATION,
                            readingStatus = ReadingStatus.UNREAD.name,
                            addedAt = now,
                            copyLabel = "${currentCopyCount + 1}冊目",
                        )
                    dao.insertCopy(copy)
                    dao.deleteWishlistByEditionId(editionId)
                    syncMutations += copy.toSyncUpsert()
                    if (previous != null) syncMutations += syncDelete("wishlistItem", editionId)
                }
                val local =
                    findLocalBookstoreBook(book.isbn13)
                        ?: return@withTransaction BookstoreChangeResult.Conflict
                syncJournal.record(syncMutations)
                BookstoreChangeResult.Updated(
                    local.copy(workId = workId, editionId = editionId),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            BookstoreChangeResult.Failure
        }

    private suspend fun findLocalBookstoreBook(isbn13: String): BookstoreBook? {
        dao.findWishlistByIsbn(isbn13)?.let { return it.toDomain() }
        val edition = dao.findEditionByIsbn(isbn13) ?: return null
        val work = dao.findWorkById(edition.workId) ?: return null
        return BookstoreBook(
            workId = work.id,
            editionId = edition.id,
            title = work.title,
            primaryAuthor = work.primaryAuthor,
            isbn13 = edition.isbn13 ?: return null,
            publisher = edition.publisher,
            publishedYear = edition.publishedYear,
            coverUrl = edition.coverUrl,
            ndcCode = edition.ndcCode,
            ndcEdition = edition.ndcEdition,
            classificationSource =
                edition.classificationSource.toEnumOrDefault(
                    ClassificationSource.UNKNOWN,
                ),
            purchaseStatus = null,
            ownedCopyCount = dao.countCopiesForEdition(edition.id),
        )
    }

    override suspend fun updateBook(
        copyId: String,
        draft: BookEditDraft,
    ): UpdateBookResult {
        val edit =
            when (val validation = bookEditValidator.validate(draft)) {
                is BookEditValidationResult.Invalid -> return UpdateBookResult.Invalid(validation.errors)
                is BookEditValidationResult.Valid -> validation.edit
            }
        return try {
            database.withTransaction {
                val previous =
                    dao.findOwnedByCopyId(copyId)?.toDomain()
                        ?: return@withTransaction UpdateBookResult.NotFound
                if (edit.locationTierId != null &&
                    database.locationDao().findTier(edit.locationTierId) == null
                ) {
                    return@withTransaction UpdateBookResult.Invalid(
                        listOf(
                            BookEditValidationError(
                                BookEditField.LOCATION,
                                UiMessage(R.string.validation_location_not_found),
                            ),
                        ),
                    )
                }
                val shelfOrderKey =
                    resolveShelfOrderKey(previous, edit)
                        ?: if (edit.locationTierId == null) {
                            null
                        } else {
                            return@withTransaction UpdateBookResult.Invalid(
                                listOf(
                                    BookEditValidationError(
                                        BookEditField.LOCATION,
                                        UiMessage(R.string.validation_insert_position_not_found),
                                    ),
                                ),
                            )
                        }
                val source =
                    if (
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
                val current = checkNotNull(dao.findOwnedByCopyId(copyId)).toDomain()
                val reorderedCopies =
                    setOfNotNull(previous.locationTierId, current.locationTierId)
                        .flatMap { tierId -> database.locationDao().getOrderedCopies(tierId) }
                        .distinctBy(OwnedCopyEntity::id)
                        .map { it.toSyncUpsert() }
                syncJournal.record(
                    listOf(current.toWorkEntity().toSyncUpsert(), current.toEditionEntity().toSyncUpsert()) +
                        reorderedCopies.ifEmpty { listOf(current.toCopyEntity().toSyncUpsert()) },
                )
                UpdateBookResult.Updated(previous = previous, current = current)
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
                val current =
                    dao.findOwnedByCopyId(previous.copyId)?.toDomain()
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
                syncJournal.record(previous.toSyncMutations())
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
    ): ShelfMoveResult =
        try {
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
                check(
                    database.locationDao().updateCopyOrder(
                        copyId,
                        FractionalOrderKey.between(left, right, copyId),
                    ) == 1,
                )
                syncJournal.record(
                    database.locationDao().getOrderedCopies(tierId).map { it.toSyncUpsert() },
                )
                ShelfMoveResult.Moved
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ShelfMoveResult.Failure
        }

    override suspend fun deleteBook(copyId: String): DeleteBookResult =
        try {
            database.withTransaction {
                val book =
                    dao.findOwnedByCopyId(copyId)?.toDomain()
                        ?: return@withTransaction DeleteBookResult.NotFound
                val syncMutations = mutableListOf<SyncMutation>(syncDelete("ownedCopy", copyId))
                syncMutations +=
                    database.readingSessionDao().findByCopyId(copyId).map { session ->
                        syncDelete("readingSession", session.id)
                    }
                val seriesMemberships = database.seriesDao().findMembershipsForWork(book.workId)
                val tagAssignments = database.tagDao().findAssignmentsForWork(book.workId)
                check(dao.deleteCopyById(copyId) == 1)
                if (dao.countCopiesForEdition(book.editionId) == 0 &&
                    dao.findWishlistByEditionId(book.editionId) == null
                ) {
                    check(dao.deleteEditionById(book.editionId) == 1)
                    syncMutations += syncDelete("edition", book.editionId)
                }
                if (dao.countEditionsForWork(book.workId) == 0) {
                    if (database.workGroupDao().findMembershipByWorkId(book.workId) == null) {
                        check(dao.deleteWorkById(book.workId) == 1)
                        syncMutations +=
                            seriesMemberships.map { membership ->
                                syncDelete("seriesMembership", membership.membershipId)
                            }
                        syncMutations +=
                            tagAssignments.map { assignment ->
                                syncDelete("tagAssignment", assignment.id)
                            }
                        syncMutations += syncDelete("work", book.workId)
                    }
                }
                syncJournal.record(syncMutations)
                DeleteBookResult.Deleted(book)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DeleteBookResult.Failure
        }

    override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult =
        try {
            database.withTransaction {
                if (dao.findCopyById(book.copyId) != null) {
                    return@withTransaction RestoreDeletedBookResult.Conflict
                }
                val originalWork = book.toWorkEntity()
                val existingWork = dao.findWorkById(book.workId)
                if (existingWork != null && existingWork != originalWork) {
                    return@withTransaction RestoreDeletedBookResult.Conflict
                }
                val existingEdition = dao.findEditionById(book.editionId)
                val isbnEdition = book.isbn13?.let { dao.findEditionByIsbn(it) }
                if (existingEdition != null && existingEdition != book.toEditionEntity()) {
                    return@withTransaction RestoreDeletedBookResult.Conflict
                }
                if (isbnEdition != null && isbnEdition.id != book.editionId) {
                    return@withTransaction RestoreDeletedBookResult.Conflict
                }
                val restoredWorkId =
                    if (
                        existingWork == null && syncJournal.hasTombstone("work", book.workId)
                    ) {
                        idFactory()
                    } else {
                        book.workId
                    }
                val restoredEditionId =
                    if (
                        existingEdition == null && syncJournal.hasTombstone("edition", book.editionId)
                    ) {
                        idFactory()
                    } else {
                        book.editionId
                    }
                val restoredCopyId =
                    if (
                        syncJournal.hasTombstone("ownedCopy", book.copyId)
                    ) {
                        idFactory()
                    } else {
                        book.copyId
                    }
                val restored =
                    book.copy(
                        workId = restoredWorkId,
                        editionId = restoredEditionId,
                        copyId = restoredCopyId,
                    )
                val expectedWork = restored.toWorkEntity()
                val expectedEdition = restored.toEditionEntity()
                val expectedCopy = restored.toCopyEntity()
                if (existingWork == null) dao.insertWork(expectedWork)
                if (existingEdition == null) dao.insertEdition(expectedEdition)
                dao.insertCopy(expectedCopy)
                syncJournal.record(
                    listOfNotNull(
                        expectedWork.toSyncUpsert().takeIf { existingWork == null },
                        expectedEdition.toSyncUpsert().takeIf { existingEdition == null },
                        expectedCopy.toSyncUpsert(),
                    ),
                )
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
    ): ImportPreviewResult =
        importPlanner.preview(
            batch = batch,
            existingBooks = dao.getLibrary().map(LibraryBookRow::toDomain),
            conflictPolicy = conflictPolicy,
            existingTags =
                database.tagDao().getAllTags().map { entity ->
                    Tag(
                        id = entity.id,
                        name = entity.name,
                        colorRole =
                            TagColorRole.entries.firstOrNull { it.name == entity.colorRole }
                                ?: TagColorRole.GRAY,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt,
                    )
                },
        )

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = importCommitter.commit(preview)

    private suspend fun writeImportedBooks(
        books: List<LibraryBook>,
        preview: LibraryImportPreview,
    ) {
        val existingEditionsByIsbn =
            dao
                .getAllEditions()
                .mapNotNull { edition -> edition.isbn13?.let { it to edition } }
                .toMap()
        val existingWorksById = dao.getAllWorks().associateBy(BookWorkEntity::id)
        val wishlistEditionIds = dao.getAllWishlistItems().mapTo(hashSetOf(), WishlistItemEntity::editionId)
        val ownedEditionIds = dao.getAllCopies().mapTo(hashSetOf(), OwnedCopyEntity::editionId)
        val resolved =
            books.map { book ->
                book.isbn13?.let(existingEditionsByIsbn::get)?.let { existing ->
                    val resolvedIds = book.copy(workId = existing.workId, editionId = existing.id)
                    val wishlistOnly = existing.id in wishlistEditionIds && existing.id !in ownedEditionIds
                    if (!wishlistOnly) {
                        resolvedIds
                    } else {
                        val existingWork = requireNotNull(existingWorksById[existing.workId])
                        resolvedIds.copy(
                            title = existingWork.title,
                            primaryAuthor = existingWork.primaryAuthor,
                            publisher = existing.publisher,
                            publishedYear = existing.publishedYear,
                            coverUrl = existing.coverUrl,
                            ndcCode = existing.ndcCode,
                            ndcEdition = existing.ndcEdition,
                            classificationSource =
                                existing.classificationSource.toEnumOrDefault(
                                    ClassificationSource.UNKNOWN,
                                ),
                        )
                    }
                } ?: book
            }
        val works = resolved.distinctBy(LibraryBook::workId).map(LibraryBook::toWorkEntity)
        val editions = resolved.distinctBy(LibraryBook::editionId).map(LibraryBook::toEditionEntity)
        val copies = resolved.map(LibraryBook::toCopyEntity)
        dao.upsertWorks(works)
        dao.upsertEditions(editions)
        dao.upsertCopies(copies)
        resolved.map(LibraryBook::editionId).distinct().forEach { editionId ->
            dao.deleteWishlistByEditionId(editionId)
        }
        val tagMutations = applyImportedTags(resolved, preview)
        syncJournal.record(
            works.map { it.toSyncUpsert() } +
                editions.map { it.toSyncUpsert() } +
                copies.map { it.toSyncUpsert() } +
                editions
                    .filter { it.id in wishlistEditionIds }
                    .map { syncDelete("wishlistItem", it.id) } +
                tagMutations,
        )
    }

    /**
     * インポートのタグ定義と各copyのタグ名を反映する。
     * 既存タグは正規化済み名で再利用し（色は変更しない）、新規タグだけ作成する。
     * 付与は（tagId, workId）で重複しないよう1件へ正規化する。
     */
    private suspend fun applyImportedTags(
        resolved: List<LibraryBook>,
        preview: LibraryImportPreview,
    ): List<SyncMutation> {
        if (preview.tagDefinitions.isEmpty() && preview.tagNamesByCopyId.isEmpty()) {
            return emptyList()
        }
        val tagDao = database.tagDao()
        val mutations = mutableListOf<SyncMutation>()
        val now = nowMillis()
        val tagsByName = tagDao.getAllTags().associateBy(TagEntity::name).toMutableMap()
        preview.tagDefinitions.forEach { definition ->
            if (tagsByName[definition.name] == null) {
                check(tagDao.countTags() < TagNameRules.MAX_TAGS)
                val entity =
                    TagEntity(
                        id = idFactory(),
                        name = definition.name,
                        colorRole = definition.colorRole.name,
                        createdAt = now,
                        updatedAt = now,
                    )
                tagDao.insertTag(entity)
                tagsByName[definition.name] = entity
                mutations += entity.toSyncUpsert()
            }
        }
        val workIdByCopyId = resolved.associate { book -> book.copyId to book.workId }
        preview.tagNamesByCopyId.forEach { (copyId, tagNames) ->
            val workId = workIdByCopyId[copyId] ?: return@forEach
            tagNames.forEach { tagName ->
                val tag = checkNotNull(tagsByName[tagName])
                if (tagDao.findAssignment(tag.id, workId) == null) {
                    val assignment =
                        TagAssignmentEntity(
                            id = idFactory(),
                            tagId = tag.id,
                            workId = workId,
                            createdAt = now,
                        )
                    tagDao.insertAssignment(assignment)
                    mutations += assignment.toSyncUpsert()
                }
            }
        }
        return mutations
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
        val insertionIndex =
            when {
                edit.locationInsertAtStart -> {
                    0
                }

                edit.locationInsertAfterCopyId != null -> {
                    val index = copies.indexOfFirst { it.id == edit.locationInsertAfterCopyId }
                    if (index < 0) return null
                    index + 1
                }

                else -> {
                    copies.size
                }
            }
        return FractionalOrderKey.between(
            copies.getOrNull(insertionIndex - 1)?.shelfOrderKey,
            copies.getOrNull(insertionIndex)?.shelfOrderKey,
            previous.copyId,
        )
    }

    private suspend fun appendOrderKey(
        tierId: String,
        copyId: String,
    ): String {
        ensureTierKeys(tierId)
        val last = database.locationDao().getOrderedCopies(tierId, copyId).lastOrNull()
        return FractionalOrderKey.between(last?.shelfOrderKey, null, copyId)
    }

    private suspend fun ensureTierKeys(tierId: String) {
        val copies = database.locationDao().getOrderedCopies(tierId)
        if (!FractionalOrderKey.requiresCompaction(copies.map { it.shelfOrderKey })) return
        copies.forEachIndexed { index, copy ->
            val key = FractionalOrderKey.compact(index, copies.size)
            check(database.locationDao().updateCopyOrder(copy.id, key) == 1)
        }
    }
}

private fun BookMetadataFailure.toAddBookFailure(): AddBookFailure =
    when (this) {
        BookMetadataFailure.OFFLINE -> AddBookFailure.OFFLINE
        BookMetadataFailure.TIMEOUT -> AddBookFailure.TIMEOUT
        BookMetadataFailure.RATE_LIMITED -> AddBookFailure.RATE_LIMITED
        BookMetadataFailure.SERVER -> AddBookFailure.SERVICE_UNAVAILABLE
        BookMetadataFailure.NETWORK -> AddBookFailure.NETWORK
        BookMetadataFailure.CLIENT -> AddBookFailure.REQUEST_REJECTED
        BookMetadataFailure.PARSE -> AddBookFailure.INVALID_RESPONSE
    }

private fun LibraryBook.toWorkEntity() =
    BookWorkEntity(
        id = workId,
        title = title,
        primaryAuthor = primaryAuthor,
    )

private fun LibraryBook.toSyncMutations(): List<SyncMutation> =
    listOf(
        toWorkEntity().toSyncUpsert(),
        toEditionEntity().toSyncUpsert(),
        toCopyEntity().toSyncUpsert(),
    )

private fun LibraryBook.toEditionEntity() =
    BookEditionEntity(
        id = editionId,
        workId = workId,
        isbn13 = isbn13,
        publisher = publisher,
        publishedYear = publishedYear,
        coverUrl = coverUrl,
        ndcCode = ndcCode,
        ndcEdition = ndcEdition,
        classificationSource = classificationSource.name,
        bibliographicSource = bibliographicSource.name,
    )

private fun BookstoreBook.toWorkEntity() =
    BookWorkEntity(
        id = workId,
        title = title,
        primaryAuthor = primaryAuthor,
    )

private fun BookstoreBook.toEditionEntity() =
    BookEditionEntity(
        id = editionId,
        workId = workId,
        isbn13 = requireNotNull(isbn13) { "Wishlist edition must have an ISBN" },
        publisher = publisher,
        publishedYear = publishedYear,
        coverUrl = coverUrl,
        ndcCode = ndcCode,
        ndcEdition = ndcEdition,
        classificationSource = classificationSource.name,
        bibliographicSource = BibliographicSource.NDL.name,
    )

private fun LibraryBook.toCopyEntity() =
    OwnedCopyEntity(
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

internal fun LibraryBookRow.toDomain(): LibraryBook =
    LibraryBook(
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
        classificationSource =
            classificationSource.toEnumOrDefault(
                ClassificationSource.UNKNOWN,
            ),
        bibliographicSource = bibliographicSource.toEnumOrDefault(BibliographicSource.NDL),
        mediaType = mediaType.toEnumOrDefault(MediaType.PHYSICAL),
        location = location,
        readingStatus = readingStatus.toEnumOrDefault(ReadingStatus.UNREAD),
        addedAt = addedAt,
        locationTierId = locationTierId,
        shelfOrderKey = shelfOrderKey,
        copyLabel = copyLabel,
    )

internal fun WishlistBookRow.toDomain(): BookstoreBook =
    BookstoreBook(
        workId = workId,
        editionId = editionId,
        title = title,
        primaryAuthor = primaryAuthor,
        isbn13 = requireNotNull(isbn13) { "Wishlist edition must have an ISBN" },
        publisher = publisher,
        publishedYear = publishedYear,
        coverUrl = coverUrl,
        ndcCode = ndcCode,
        ndcEdition = ndcEdition,
        classificationSource = classificationSource.toEnumOrDefault(ClassificationSource.UNKNOWN),
        purchaseStatus = status.toEnumOrDefault(PurchaseStatus.WANTED),
        ownedCopyCount = ownedCopyCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T = enumValues<T>().firstOrNull { it.name == this } ?: default

private fun toScanSessions(rows: List<ScanSessionAttemptRow>): List<ScanSession> =
    rows.groupBy(ScanSessionAttemptRow::sessionId).map { (sessionId, sessionRows) ->
        val session = sessionRows.first()
        ScanSession(
            id = sessionId,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            attempts =
                sessionRows.mapNotNull { row ->
                    val attemptId = row.attemptId ?: return@mapNotNull null
                    ScanAttempt(
                        id = attemptId,
                        sessionId = sessionId,
                        isbn = row.isbn.orEmpty(),
                        outcome =
                            row.outcome?.toEnumOrDefault(ScanAttemptOutcome.FAILURE)
                                ?: ScanAttemptOutcome.FAILURE,
                        copyId = row.copyId,
                        attemptedAt = row.attemptedAt ?: session.startedAt,
                        undoneAt = row.undoneAt,
                    )
                },
        )
    }

private fun LibraryBook.snapshotHash(): String {
    val canonical =
        listOf(
            copyId,
            workId,
            editionId,
            title,
            primaryAuthor,
            isbn13,
            publisher,
            publishedYear?.toString(),
            coverUrl,
            ndcCode,
            ndcEdition,
            classificationSource.name,
            mediaType.name,
            location,
            readingStatus.name,
            addedAt.toString(),
            locationTierId,
            shelfOrderKey,
            copyLabel,
        ).joinToString(separator = "") { value ->
            value?.let { "${it.length}:$it" } ?: "-1:"
        }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun BookEditionEntity.toReconciliationSnapshot(work: BookWorkEntity) =
    ReconciliationEditionSnapshot(
        workId = work.id,
        editionId = id,
        title = work.title,
        primaryAuthor = work.primaryAuthor,
        isbn13 = isbn13,
        publisher = publisher,
        publishedYear = publishedYear,
        coverUrl = coverUrl,
        ndcCode = ndcCode,
        ndcEdition = ndcEdition,
        classificationSource = classificationSource.toEnumOrDefault(ClassificationSource.UNKNOWN),
        bibliographicSource = bibliographicSource.toEnumOrDefault(BibliographicSource.NDL),
    )

private const val RECENT_SCAN_SESSION_LIMIT = 20
private const val MAX_RECORDED_ISBN_LENGTH = 32

private const val MAX_COPY_LABEL_LENGTH = 100
