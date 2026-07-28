package dev.ndcshelf.app

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookstoreBook
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.BookstoreChangeResult
import dev.ndcshelf.app.domain.repository.BookstoreLookupResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelScanTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun retryableLookupFailureKeepsIsbnAndCanBeRetried() = runTest(dispatcher) {
        val repository = FakeRepository(
            ArrayDeque(
                listOf(
                    AddBookResult.Failure(AddBookFailure.OFFLINE, ISBN),
                    AddBookResult.Added(book()),
                ),
            ),
        )
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.submitIsbn(ISBN)

        val failure = viewModel.scanState.value as ScanUiState.Error
        assertEquals(ScanFailure.OFFLINE, failure.failure)
        assertEquals(ISBN, failure.retryIsbn)

        viewModel.retryScan()

        assertEquals(2, repository.lookupCount)
        assertEquals(ScanUiState.Added(ISBN, "題名"), viewModel.scanState.value)
    }

    @Test
    fun nonRetryableFailureDoesNotExposeRetry() = runTest(dispatcher) {
        val repository = FakeRepository(
            ArrayDeque(
                listOf(AddBookResult.Failure(AddBookFailure.INVALID_RESPONSE, ISBN)),
            ),
        )
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.submitIsbn(ISBN)

        val failure = viewModel.scanState.value as ScanUiState.Error
        assertEquals(ScanFailure.INVALID_RESPONSE, failure.failure)
        assertNull(failure.retryIsbn)
        viewModel.retryScan()
        assertEquals(1, repository.lookupCount)
    }

    @Test
    fun duplicateCanBeConfirmedOrAddedAsAnotherCopy() = runTest(dispatcher) {
        val existing = book()
        val repository = FakeRepository(
            ArrayDeque(listOf(AddBookResult.Duplicate(existing, copyCount = 2))),
        ).apply {
            anotherCopyResult = AddBookResult.Added(existing.copy(copyId = "copy-3", copyLabel = "保存用"))
        }
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.submitIsbn(ISBN)

        assertEquals(ScanUiState.Duplicate(ISBN, "題名", 2), viewModel.scanState.value)
        viewModel.addDuplicateCopy(" 保存用 ")
        assertEquals(" 保存用 ", repository.requestedCopyLabel)
        assertEquals(ScanUiState.Added(ISBN, "題名"), viewModel.scanState.value)
    }

    @Test
    fun bookstoreLookupAndPurchaseTransitionExposeCurrentStatus() = runTest(dispatcher) {
        val candidate = bookstoreBook(PurchaseStatus.WANTED, ownedCount = 1)
        val purchased = candidate.copy(purchaseStatus = null, ownedCopyCount = 2)
        val repository = FakeRepository(ArrayDeque()).apply {
            bookstoreLookupResult = BookstoreLookupResult.Found(candidate)
            bookstoreChangeResult = BookstoreChangeResult.Updated(purchased)
        }
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.lookupBookstore(ISBN)

        assertEquals(BookstoreUiState.Result(candidate), viewModel.bookstoreState.value)
        viewModel.changePurchaseState(PurchaseTransition.PURCHASED)
        assertEquals(PurchaseTransition.PURCHASED, repository.requestedTransition)
        assertEquals(BookstoreUiState.Result(purchased), viewModel.bookstoreState.value)
    }

    @Test
    fun bookstoreConflictCanReloadPersistedState() = runTest(dispatcher) {
        val candidate = bookstoreBook(PurchaseStatus.WANTED, ownedCount = 0)
        val repository = FakeRepository(ArrayDeque()).apply {
            bookstoreLookupResult = BookstoreLookupResult.Found(candidate)
            bookstoreChangeResult = BookstoreChangeResult.Conflict
        }
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)
        viewModel.lookupBookstore(ISBN)

        viewModel.changePurchaseState(PurchaseTransition.RESERVED)

        assertEquals(
            BookstoreUiState.Error(ScanFailure.SAVE, ISBN, retryIsbn = ISBN),
            viewModel.bookstoreState.value,
        )
        viewModel.retryBookstoreLookup()
        assertEquals(BookstoreUiState.Result(candidate), viewModel.bookstoreState.value)
    }

    private class FakeRepository(
        private val results: ArrayDeque<AddBookResult>,
    ) : LibraryRepository {
        private val books = MutableStateFlow(emptyList<LibraryBook>())
        var lookupCount = 0
        var anotherCopyResult: AddBookResult = AddBookResult.Failure(AddBookFailure.SAVE, ISBN)
        var requestedCopyLabel: String? = null
        var bookstoreLookupResult: BookstoreLookupResult =
            BookstoreLookupResult.Failure(AddBookFailure.SAVE)
        var bookstoreChangeResult: BookstoreChangeResult = BookstoreChangeResult.Failure
        var requestedTransition: PurchaseTransition? = null

        override fun observeLibrary(): Flow<List<LibraryBook>> = books

        override suspend fun addFromIsbn(rawIsbn: String): AddBookResult {
            lookupCount += 1
            return results.removeFirst()
        }

        override suspend fun addAnotherCopy(rawIsbn: String, copyLabel: String): AddBookResult {
            requestedCopyLabel = copyLabel
            return anotherCopyResult
        }

        override suspend fun lookupBookstore(rawIsbn: String): BookstoreLookupResult =
            bookstoreLookupResult

        override suspend fun changePurchaseState(
            book: BookstoreBook,
            transition: PurchaseTransition,
        ): BookstoreChangeResult {
            requestedTransition = transition
            return bookstoreChangeResult
        }

        override suspend fun updateBook(copyId: String, draft: BookEditDraft): UpdateBookResult =
            error("Not used")

        override suspend fun restoreBook(
            previous: LibraryBook,
            expectedCurrent: LibraryBook,
        ): Boolean = error("Not used")

        override suspend fun deleteBook(copyId: String): DeleteBookResult = error("Not used")

        override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult =
            error("Not used")

        override suspend fun previewImport(
            batch: LibraryImportBatch,
            conflictPolicy: ImportConflictPolicy,
        ): ImportPreviewResult = error("Not used")

        override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult =
            error("Not used")
    }

    private companion object {
        const val ISBN = "9784820418078"

        fun book() = LibraryBook(
            copyId = "copy",
            workId = "work",
            editionId = "edition",
            title = "題名",
            primaryAuthor = "著者",
            isbn13 = ISBN,
            publisher = null,
            publishedYear = null,
            coverUrl = null,
            ndcCode = null,
            ndcEdition = null,
            classificationSource = ClassificationSource.UNKNOWN,
            mediaType = MediaType.PHYSICAL,
            location = "棚",
            readingStatus = ReadingStatus.UNREAD,
            addedAt = 1,
        )

        fun bookstoreBook(status: PurchaseStatus?, ownedCount: Int) = BookstoreBook(
            workId = "work",
            editionId = "edition",
            title = "題名",
            primaryAuthor = "著者",
            isbn13 = ISBN,
            publisher = "出版社",
            publishedYear = 2024,
            coverUrl = null,
            ndcCode = "014.45",
            ndcEdition = "NDC10",
            classificationSource = ClassificationSource.NDL,
            purchaseStatus = status,
            ownedCopyCount = ownedCount,
        )
    }
}
