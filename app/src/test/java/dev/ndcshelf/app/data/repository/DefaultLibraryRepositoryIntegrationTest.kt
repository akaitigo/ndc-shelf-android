package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.BookMetadata
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ManualBookDraft
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.BookstoreChangeResult
import dev.ndcshelf.app.domain.repository.BookstoreLookupResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.ScanUndoResult
import dev.ndcshelf.app.domain.repository.ManualBookResult
import dev.ndcshelf.app.domain.repository.ManualReconciliationApplyResult
import dev.ndcshelf.app.domain.repository.ManualReconciliationLookupResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultLibraryRepositoryIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addDuplicateUpdateAndDelete_useTheRealDaoAndTransactions() = runBlocking {
        var metadataCalls = 0
        val repository = repository(
            ids = listOf("work-new", "edition-new", "copy-new"),
            service = BookMetadataService {
                metadataCalls += 1
                BookMetadataLookupResult.Found(metadata())
            },
        )

        val added = repository.addFromIsbn(ISBN) as AddBookResult.Added

        assertEquals("work-new", added.book.workId)
        assertEquals("題名", added.book.title)
        assertEquals("著者A・著者B", added.book.primaryAuthor)
        assertEquals(ClassificationSource.NDL, added.book.classificationSource)
        assertEquals(1_700_000_000_000L, added.book.addedAt)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(1, database.libraryDao().getAllEditions().size)
        assertEquals(1, database.libraryDao().getAllCopies().size)

        val duplicate = repository.addFromIsbn(ISBN) as AddBookResult.Duplicate
        assertEquals(added.book, duplicate.book)
        assertEquals(1, metadataCalls)

        val updated = repository.updateBook(
            added.book.copyId,
            BookEditDraft(
                title = "更新後",
                primaryAuthor = "新著者",
                publisher = "新出版社",
                publishedYear = "2025",
                ndcCode = "913.6",
                ndcEdition = "NDC10",
                location = "居間・棚B",
                readingStatus = ReadingStatus.READ,
            ),
        ) as UpdateBookResult.Updated
        assertEquals("更新後", updated.current.title)
        assertEquals(ClassificationSource.MANUAL, updated.current.classificationSource)
        assertEquals(
            "居間・棚B",
            database.libraryDao().findOwnedByCopyId(added.book.copyId)?.location,
        )

        val deleted = repository.deleteBook(added.book.copyId) as DeleteBookResult.Deleted
        assertEquals(updated.current, deleted.book)
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
    }

    @Test
    fun insertFailure_rollsBackTheWorkInsertedEarlierInTheTransaction() = runBlocking {
        database.libraryDao().insertWork(BookWorkEntity("existing-work", "既存", "既存著者"))
        database.libraryDao().insertEdition(
            BookEditionEntity(
                id = "edition-collision",
                workId = "existing-work",
                isbn13 = OTHER_ISBN,
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = ClassificationSource.UNKNOWN.name,
            ),
        )
        val repository = repository(
            ids = listOf("work-must-rollback", "edition-collision", "copy-new"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )

        val result = repository.addFromIsbn(ISBN)

        assertTrue(result is AddBookResult.Failure)
        assertEquals(AddBookFailure.SAVE, (result as AddBookResult.Failure).reason)
        assertNull(database.libraryDao().findWorkById("work-must-rollback"))
        assertEquals(listOf("existing-work"), database.libraryDao().getAllWorks().map { it.id })
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
    }

    @Test
    fun anotherCopy_reusesEditionAndDeletingOneCopyKeepsTheOther() = runBlocking {
        var metadataCalls = 0
        val repository = repository(
            ids = listOf("work-1", "edition-1", "copy-1", "copy-2"),
            service = BookMetadataService {
                metadataCalls += 1
                BookMetadataLookupResult.Found(metadata())
            },
        )
        val first = (repository.addFromIsbn(ISBN) as AddBookResult.Added).book
        val duplicate = repository.addFromIsbn(ISBN) as AddBookResult.Duplicate

        val second = (repository.addAnotherCopy(ISBN, "保存用") as AddBookResult.Added).book

        assertEquals(1, duplicate.copyCount)
        assertEquals("保存用", second.copyLabel)
        assertEquals(first.editionId, second.editionId)
        assertEquals(1, metadataCalls)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(1, database.libraryDao().getAllEditions().size)
        assertEquals(2, database.libraryDao().getAllCopies().size)

        repository.deleteBook(first.copyId)
        assertEquals(listOf(second.copyId), database.libraryDao().getAllCopies().map { it.id })
        assertEquals(1, database.libraryDao().getAllEditions().size)
        assertEquals(1, database.libraryDao().getAllWorks().size)

        repository.deleteBook(second.copyId)
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
    }

    @Test
    fun concurrentDuplicateAdds_createDistinctCopiesInOneEdition() = runBlocking {
        val repository = repository(
            ids = listOf("work-1", "edition-1", "copy-1", "copy-2", "copy-3"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        repository.addFromIsbn(ISBN)

        val results = coroutineScope {
            listOf("保存用", "貸出用").map { label ->
                async(Dispatchers.Default) { repository.addAnotherCopy(ISBN, label) }
            }.awaitAll()
        }

        assertTrue(results.all { it is AddBookResult.Added })
        val copies = database.libraryDao().getAllCopies()
        assertEquals(3, copies.size)
        assertEquals(3, copies.map { it.id }.distinct().size)
        assertEquals(setOf("1冊目", "保存用", "貸出用"), copies.map { it.copyLabel }.toSet())
        assertEquals(1, copies.map { it.editionId }.distinct().size)
    }

    @Test
    fun importWritesMultipleCopiesForOneEditionAtomically() = runBlocking {
        val repository = repository(emptyList(), BookMetadataService { BookMetadataLookupResult.NotFound })
        val first = importBook("first", ISBN).copy(copyLabel = "保存用")
        val second = first.copy(copyId = "copy-second", copyLabel = "貸出用")
        val preview = LibraryImportPreview(
            additions = listOf(first, second),
            updates = emptyList(),
            skippedCount = 0,
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
            existingSnapshot = emptyList(),
        )

        val result = repository.applyImport(preview) as ImportApplyResult.Applied

        assertEquals(2, result.addedCount)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(1, database.libraryDao().getAllEditions().size)
        assertEquals(listOf("保存用", "貸出用"), database.libraryDao().getAllCopies().map { it.copyLabel })
    }

    @Test
    fun bookstoreStatesPersistOfflineAndPurchaseCreatesOwnedCopy() = runBlocking {
        var metadataCalls = 0
        val repository = repository(
            ids = listOf("work-wish", "edition-wish", "copy-purchased"),
            service = BookMetadataService {
                metadataCalls += 1
                BookMetadataLookupResult.Found(metadata())
            },
        )
        val candidate = (repository.lookupBookstore(ISBN) as BookstoreLookupResult.Found).book

        val wanted = repository.changePurchaseState(candidate, PurchaseTransition.WANTED)
            as BookstoreChangeResult.Updated
        val offlineLookup = repository.lookupBookstore(ISBN) as BookstoreLookupResult.Found
        val reserved = repository.changePurchaseState(wanted.book, PurchaseTransition.RESERVED)
            as BookstoreChangeResult.Updated

        assertEquals(PurchaseStatus.WANTED, offlineLookup.book.purchaseStatus)
        assertEquals(PurchaseStatus.RESERVED, reserved.book.purchaseStatus)
        assertEquals(1, metadataCalls)
        assertEquals(1, database.libraryDao().getAllWishlistItems().size)
        assertTrue(database.libraryDao().getAllCopies().isEmpty())

        val purchased = repository.changePurchaseState(reserved.book, PurchaseTransition.PURCHASED)
            as BookstoreChangeResult.Updated

        assertNull(purchased.book.purchaseStatus)
        assertEquals(1, purchased.book.ownedCopyCount)
        assertTrue(database.libraryDao().getAllWishlistItems().isEmpty())
        val copy = database.libraryDao().getAllCopies().single()
        assertEquals("copy-purchased", copy.id)
        assertEquals("1冊目", copy.copyLabel)
        assertEquals("edition-wish", copy.editionId)
    }

    @Test
    fun concurrentCandidateUpdatesKeepOneWishlistRow() = runBlocking {
        val repository = repository(
            ids = listOf("work-wish", "edition-wish"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val candidate = (repository.lookupBookstore(ISBN) as BookstoreLookupResult.Found).book

        val results = coroutineScope {
            listOf(PurchaseTransition.WANTED, PurchaseTransition.RESERVED).map { transition ->
                async(Dispatchers.Default) {
                    repository.changePurchaseState(candidate, transition)
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it is BookstoreChangeResult.Updated })
        assertEquals(1, results.count { it is BookstoreChangeResult.Conflict })
        val rows = database.libraryDao().getAllWishlistItems()
        assertEquals(1, rows.size)
        assertTrue(rows.single().status in setOf("WANTED", "RESERVED"))
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(1, database.libraryDao().getAllEditions().size)
    }

    @Test
    fun concurrentPurchasesCreateExactlyOneOwnedCopy() = runBlocking {
        val repository = repository(
            ids = listOf("work-wish", "edition-wish", "copy-purchased"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val candidate = (repository.lookupBookstore(ISBN) as BookstoreLookupResult.Found).book
        val wanted = repository.changePurchaseState(candidate, PurchaseTransition.WANTED)
            as BookstoreChangeResult.Updated

        val results = coroutineScope {
            List(2) {
                async(Dispatchers.Default) {
                    repository.changePurchaseState(wanted.book, PurchaseTransition.PURCHASED)
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it is BookstoreChangeResult.Updated })
        assertEquals(1, results.count { it is BookstoreChangeResult.Conflict })
        assertEquals(1, database.libraryDao().getAllCopies().size)
        assertTrue(database.libraryDao().getAllWishlistItems().isEmpty())
    }

    @Test
    fun deletingLastOwnedCopyKeepsWishlistEdition() = runBlocking {
        val repository = repository(
            ids = listOf("work-1", "edition-1", "copy-1"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val owned = (repository.addFromIsbn(ISBN) as AddBookResult.Added).book
        val local = (repository.lookupBookstore(ISBN) as BookstoreLookupResult.Found).book
        repository.changePurchaseState(local, PurchaseTransition.WANTED)

        repository.deleteBook(owned.copyId)

        assertTrue(database.libraryDao().getAllCopies().isEmpty())
        assertEquals("WANTED", database.libraryDao().getAllWishlistItems().single().status)
        assertEquals("edition-1", database.libraryDao().getAllEditions().single().id)
        assertEquals("work-1", database.libraryDao().getAllWorks().single().id)
    }

    @Test
    fun ownedImportConvertsMatchingWishlistWithoutDuplicateEdition() = runBlocking {
        val repository = repository(
            ids = listOf("work-wish", "edition-wish"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val candidate = (repository.lookupBookstore(ISBN) as BookstoreLookupResult.Found).book
        repository.changePurchaseState(candidate, PurchaseTransition.RESERVED)
        val imported = importBook("imported", ISBN)
        val preview = LibraryImportPreview(
            additions = listOf(imported),
            updates = emptyList(),
            skippedCount = 0,
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
            existingSnapshot = emptyList(),
        )

        val result = repository.applyImport(preview) as ImportApplyResult.Applied

        assertEquals(1, result.addedCount)
        assertTrue(database.libraryDao().getAllWishlistItems().isEmpty())
        assertEquals(1, database.libraryDao().getAllEditions().size)
        val copy = database.libraryDao().getAllCopies().single()
        assertEquals("edition-wish", copy.editionId)
    }

    @Test
    fun scanSessionRecordsAddedAndDuplicateThenRollsBackAddedCopy() = runBlocking {
        val repository = repository(
            ids = listOf("session-1", "work-1", "edition-1", "copy-1", "attempt-1", "attempt-2"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val sessionId = repository.startScanSession()
        assertEquals("session-1", sessionId)
        val added = repository.addFromIsbn(ISBN)
        assertTrue(repository.recordScanAttempt(sessionId!!, ISBN, added))
        val duplicate = repository.addFromIsbn(ISBN)
        assertTrue(repository.recordScanAttempt(sessionId, ISBN, duplicate))

        val session = repository.observeScanSessions().first().single()
        assertTrue(session.isActive)
        assertEquals(2, session.attempts.size)
        assertEquals(1, session.activeAddedCount)

        assertEquals(ScanUndoResult.Undone(1), repository.undoScanSession(sessionId))
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
        assertEquals(0, repository.observeScanSessions().first().single().activeAddedCount)
    }

    @Test
    fun scanUndoRejectsCopyEditedOutsideSessionWithoutDeletingAnything() = runBlocking {
        val repository = repository(
            ids = listOf("session-1", "work-1", "edition-1", "copy-1", "attempt-1"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val sessionId = repository.startScanSession()!!
        val added = repository.addFromIsbn(ISBN) as AddBookResult.Added
        repository.recordScanAttempt(sessionId, ISBN, added)
        database.libraryDao().updateCopy(
            copyId = added.book.copyId,
            location = "書斎",
            readingStatus = added.book.readingStatus.name,
            copyLabel = added.book.copyLabel,
        )

        assertEquals(ScanUndoResult.Conflict, repository.undoScanSession(sessionId))
        assertEquals("書斎", database.libraryDao().findCopyById(added.book.copyId)?.location)
    }

    @Test
    fun scanSessionRollbackIsAtomicWhenOneOfMultipleCopiesWasEdited() = runBlocking {
        val repository = repository(
            ids = listOf(
                "session-1",
                "work-1", "edition-1", "copy-1", "attempt-1",
                "copy-2", "attempt-2",
            ),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val sessionId = repository.startScanSession()!!
        val first = repository.addFromIsbn(ISBN) as AddBookResult.Added
        repository.recordScanAttempt(sessionId, ISBN, first)
        val second = repository.addAnotherCopy(ISBN, "保存用") as AddBookResult.Added
        repository.recordScanAttempt(sessionId, ISBN, second)
        database.libraryDao().updateCopy(
            copyId = second.book.copyId,
            location = "書斎",
            readingStatus = second.book.readingStatus.name,
            copyLabel = second.book.copyLabel,
        )

        assertEquals(ScanUndoResult.Conflict, repository.undoScanSession(sessionId))
        assertEquals(setOf("copy-1", "copy-2"), database.libraryDao().getAllCopies().map { it.id }.toSet())
        assertTrue(database.libraryDao().getAllScanAttempts().all { it.undoneAt == null })
    }

    @Test
    fun unfinishedScanSessionIsRecoveredAndCanBeSafelyFinished() = runBlocking {
        val firstRepository = repository(
            ids = listOf("session-1"),
            service = BookMetadataService { BookMetadataLookupResult.NotFound },
        )
        assertEquals("session-1", firstRepository.startScanSession())
        val recreatedRepository = repository(
            ids = listOf("session-2"),
            service = BookMetadataService { BookMetadataLookupResult.NotFound },
        )

        assertEquals("session-1", recreatedRepository.startScanSession())
        assertTrue(recreatedRepository.finishScanSession("session-1"))
        assertNull(database.libraryDao().findActiveScanSession())
    }

    @Test
    fun finishedScanSessionRejectsLaterAttemptRecording() = runBlocking {
        val repository = repository(
            ids = listOf("session-1"),
            service = BookMetadataService { BookMetadataLookupResult.NotFound },
        )
        val sessionId = repository.startScanSession()!!
        assertTrue(repository.finishScanSession(sessionId))

        assertTrue(
            !repository.recordScanAttempt(sessionId, ISBN, AddBookResult.NotFound(ISBN)),
        )
        assertTrue(database.libraryDao().getAllScanAttempts().isEmpty())
    }

    @Test
    fun cancellationFromMetadataLookup_isPropagatedWithoutWrites() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val repository = repository(
            ids = emptyList(),
            service = BookMetadataService { throw cancellation },
        )

        try {
            repository.addFromIsbn(ISBN)
            fail("CancellationException expected")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
    }

    @Test
    fun unknownPersistedEnums_fallBackToSafeDomainValues() = runBlocking {
        database.libraryDao().insertWork(BookWorkEntity("work-unknown", "題名", "著者"))
        database.libraryDao().insertEdition(
            BookEditionEntity(
                id = "edition-unknown",
                workId = "work-unknown",
                isbn13 = ISBN,
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = "FUTURE_SOURCE",
            ),
        )
        database.libraryDao().insertCopy(
            OwnedCopyEntity(
                id = "copy-unknown",
                editionId = "edition-unknown",
                mediaType = "FUTURE_MEDIA",
                location = "棚",
                readingStatus = "FUTURE_STATUS",
                addedAt = 1,
            ),
        )

        val book = requireNotNull(
            database.libraryDao().findOwnedByCopyId("copy-unknown"),
        ).toDomain()

        assertEquals(ClassificationSource.UNKNOWN, book.classificationSource)
        assertEquals(MediaType.PHYSICAL, book.mediaType)
        assertEquals(ReadingStatus.UNREAD, book.readingStatus)
    }

    @Test
    fun invalidImportConstraint_rollsBackAllThreeTables() = runBlocking {
        val repository = repository(
            emptyList(),
            BookMetadataService { BookMetadataLookupResult.NotFound },
        )
        val first = importBook("first", ISBN)
        val conflicting = importBook("second", ISBN)
        val preview = LibraryImportPreview(
            additions = listOf(first, conflicting),
            updates = emptyList(),
            skippedCount = 0,
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
            existingSnapshot = emptyList(),
        )

        val result = repository.applyImport(preview)

        assertTrue(result is ImportApplyResult.Failure)
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
    }

    @Test
    fun manualTitleOnlyRegistration_worksOfflineAndPersistsStableIdsAndSource() = runBlocking {
        var metadataCalls = 0
        val repository = repository(
            ids = listOf("manual-work", "manual-edition", "manual-copy"),
            service = BookMetadataService {
                metadataCalls += 1
                BookMetadataLookupResult.NotFound
            },
        )

        val result = repository.addManualBook(
            ManualBookDraft(title = " 郷土資料 ", mediaType = MediaType.DIGITAL),
        ) as ManualBookResult.Added

        assertEquals(0, metadataCalls)
        assertEquals("manual-work", result.book.workId)
        assertEquals("manual-edition", result.book.editionId)
        assertEquals("manual-copy", result.book.copyId)
        assertNull(result.book.isbn13)
        assertEquals(BibliographicSource.MANUAL, result.book.bibliographicSource)
        assertEquals(MediaType.DIGITAL, result.book.mediaType)
        assertEquals(result.book, database.libraryDao().findOwnedByCopyId("manual-copy")?.toDomain())
    }

    @Test
    fun manualRegistrationWithExistingIsbn_reportsDuplicateWithoutWriting() = runBlocking {
        val repository = repository(
            ids = listOf("work", "edition", "copy"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        repository.addFromIsbn(ISBN)

        val result = repository.addManualBook(ManualBookDraft(title = "別タイトル", isbn = ISBN))
            as ManualBookResult.Duplicate

        assertEquals(ISBN, result.isbn13)
        assertEquals(1, result.copyCount)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(1, database.libraryDao().getAllCopies().size)
    }

    @Test
    fun laterNdlReconciliation_requiresPreviewAndPreservesIdsUntilConfirmation() = runBlocking {
        val repository = repository(
            ids = listOf("manual-work", "manual-edition", "manual-copy"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val manual = (repository.addManualBook(ManualBookDraft(title = "仮題"))
            as ManualBookResult.Added).book

        val lookup = repository.previewManualReconciliation(manual.copyId, ISBN)
            as ManualReconciliationLookupResult.Ready
        assertEquals("仮題", database.libraryDao().findWorkById(manual.workId)?.title)
        assertNull(database.libraryDao().findEditionById(manual.editionId)?.isbn13)

        assertSame(
            ManualReconciliationApplyResult.Applied,
            repository.confirmManualReconciliation(lookup.preview),
        )
        val reconciled = requireNotNull(database.libraryDao().findOwnedByCopyId(manual.copyId)).toDomain()
        assertEquals(manual.workId, reconciled.workId)
        assertEquals(manual.editionId, reconciled.editionId)
        assertEquals(ISBN, reconciled.isbn13)
        assertEquals("題名", reconciled.title)
        assertEquals(BibliographicSource.NDL, reconciled.bibliographicSource)
    }

    @Test
    fun reconciliationWithExistingIsbn_mergesCopiesOnlyAfterConfirmation() = runBlocking {
        val repository = repository(
            ids = listOf(
                "ndl-work", "ndl-edition", "ndl-copy",
                "manual-work", "manual-edition", "manual-copy",
            ),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val ndl = (repository.addFromIsbn(ISBN) as AddBookResult.Added).book
        val manual = (repository.addManualBook(ManualBookDraft(title = "仮題"))
            as ManualBookResult.Added).book

        val preview = (repository.previewManualReconciliation(manual.copyId, ISBN)
            as ManualReconciliationLookupResult.Ready).preview
        assertEquals(ndl.editionId, preview.existingEditionId)
        assertEquals(2, database.libraryDao().getAllEditions().size)

        assertSame(
            ManualReconciliationApplyResult.Applied,
            repository.confirmManualReconciliation(preview),
        )
        val copies = database.libraryDao().getAllCopies()
        assertEquals(2, copies.size)
        assertTrue(copies.all { it.editionId == ndl.editionId })
        assertNull(database.libraryDao().findEditionById(manual.editionId))
        assertNull(database.libraryDao().findWorkById(manual.workId))
    }

    @Test
    fun reconciliationRejectsStalePreviewWithoutOverwritingConcurrentEdit() = runBlocking {
        val repository = repository(
            ids = listOf("manual-work", "manual-edition", "manual-copy"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )
        val manual = (repository.addManualBook(ManualBookDraft(title = "仮題"))
            as ManualBookResult.Added).book
        val preview = (repository.previewManualReconciliation(manual.copyId, ISBN)
            as ManualReconciliationLookupResult.Ready).preview
        database.libraryDao().updateWork(manual.workId, "並行編集", "著者不明")

        assertSame(
            ManualReconciliationApplyResult.Conflict,
            repository.confirmManualReconciliation(preview),
        )
        assertEquals("並行編集", database.libraryDao().findWorkById(manual.workId)?.title)
        assertNull(database.libraryDao().findEditionById(manual.editionId)?.isbn13)
    }

    private fun repository(
        ids: List<String>,
        service: BookMetadataService,
    ): DefaultLibraryRepository {
        val remainingIds = ConcurrentLinkedQueue(ids)
        return DefaultLibraryRepository(
            database = database,
            metadataService = service,
            idFactory = { requireNotNull(remainingIds.poll()) },
            nowMillis = { 1_700_000_000_000L },
        )
    }

    private fun metadata() = BookMetadata(
        title = "題名",
        authors = listOf("著者A", "著者B"),
        publisher = "出版社",
        publishedYear = 2024,
        editionStatement = null,
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        coverUrl = "https://ndlsearch.ndl.go.jp/thumbnail/$ISBN.jpg",
    )

    private fun importBook(id: String, isbn: String) = LibraryBook(
        copyId = "copy-$id",
        workId = "work-$id",
        editionId = "edition-$id",
        title = "題名$id",
        primaryAuthor = "著者",
        isbn13 = isbn,
        publisher = null,
        publishedYear = 2024,
        coverUrl = null,
        ndcCode = null,
        ndcEdition = null,
        classificationSource = ClassificationSource.UNKNOWN,
        mediaType = MediaType.PHYSICAL,
        location = "棚",
        readingStatus = ReadingStatus.UNREAD,
        addedAt = 1,
    )

    private companion object {
        const val ISBN = "9784820418078"
        const val OTHER_ISBN = "9784101010014"
    }
}
