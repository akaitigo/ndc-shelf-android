package dev.ndcshelf.app

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelBookDeleteTest {
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
    fun `deleted copy snapshot can be restored`() = runTest(dispatcher) {
        val book = sampleBook()
        val repository = FakeRepository(
            deleteResult = DeleteBookResult.Deleted(book),
            restoreResult = RestoreDeletedBookResult.Restored,
        )
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.deleteBook(book.copyId)
        assertEquals(BookDeleteUiState.Deleted(book), viewModel.bookDeleteState.value)

        viewModel.undoLastBookDeletion()

        assertEquals(BookDeleteUiState.Restored, viewModel.bookDeleteState.value)
        assertEquals(book, repository.restoredBook)
    }

    @Test
    fun `restore conflict becomes a visible failure state`() = runTest(dispatcher) {
        val book = sampleBook()
        val repository = FakeRepository(
            deleteResult = DeleteBookResult.Deleted(book),
            restoreResult = RestoreDeletedBookResult.Conflict,
        )
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.deleteBook(book.copyId)
        viewModel.undoLastBookDeletion()

        val error = viewModel.bookDeleteState.value as BookDeleteUiState.Error
        assertEquals(BookDeleteFailure.RESTORE_CONFLICT, error.failure)
    }

    private fun sampleBook() = LibraryBook(
        copyId = "copy-1",
        workId = "work-1",
        editionId = "edition-1",
        title = "本の題名",
        primaryAuthor = "著者",
        isbn13 = "9784820418078",
        publisher = null,
        publishedYear = 2024,
        coverUrl = null,
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        classificationSource = ClassificationSource.NDL,
        mediaType = MediaType.PHYSICAL,
        location = "本棚A",
        readingStatus = ReadingStatus.UNREAD,
        addedAt = 1_700_000_000_000L,
    )

    private class FakeRepository(
        private val deleteResult: DeleteBookResult,
        private val restoreResult: RestoreDeletedBookResult,
    ) : LibraryRepository {
        private val books = MutableStateFlow(emptyList<LibraryBook>())
        var restoredBook: LibraryBook? = null

        override fun observeLibrary(): Flow<List<LibraryBook>> = books

        override suspend fun addFromIsbn(rawIsbn: String): AddBookResult = error("Not used")

        override suspend fun updateBook(copyId: String, draft: BookEditDraft): UpdateBookResult =
            error("Not used")

        override suspend fun restoreBook(
            previous: LibraryBook,
            expectedCurrent: LibraryBook,
        ): Boolean = error("Not used")

        override suspend fun deleteBook(copyId: String): DeleteBookResult = deleteResult

        override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult {
            restoredBook = book
            return restoreResult
        }

        override suspend fun previewImport(
            batch: LibraryImportBatch,
            conflictPolicy: ImportConflictPolicy,
        ): ImportPreviewResult = error("Not used")

        override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult =
            error("Not used")
    }
}
