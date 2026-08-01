package dev.ndcshelf.app

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.BookEditField
import dev.ndcshelf.app.domain.model.BookEditValidationError
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.domain.text.UiMessage
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
class MainViewModelBookEditTest {
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
    fun `validation errors stay associated with edited copy`() = runTest(dispatcher) {
        val repository = FakeRepository().apply {
            updateResult = UpdateBookResult.Invalid(
                listOf(BookEditValidationError(
                    BookEditField.TITLE,
                    UiMessage(R.string.validation_required),
                )),
            )
        }
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.saveBookEdit("copy-1", draft())

        val state = viewModel.bookEditState.value as BookEditUiState.Invalid
        assertEquals("copy-1", state.copyId)
        assertEquals(BookEditField.TITLE, state.errors.single().field)
    }

    @Test
    fun `saved edit can be undone with guarded snapshots`() = runTest(dispatcher) {
        val previous = sampleBook(title = "旧題")
        val current = previous.copy(title = "新題", classificationSource = ClassificationSource.MANUAL)
        val repository = FakeRepository().apply {
            updateResult = UpdateBookResult.Updated(previous, current)
            restoreResult = true
        }
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)

        viewModel.saveBookEdit(previous.copyId, draft(title = "新題"))
        assertTrue(viewModel.bookEditState.value is BookEditUiState.Saved)

        viewModel.undoLastBookEdit()

        assertEquals(BookEditUiState.Undone, viewModel.bookEditState.value)
        assertEquals(previous to current, repository.restoredSnapshots)
    }

    private fun draft(title: String = "本の題名") = BookEditDraft(
        title = title,
        primaryAuthor = "著者",
        publisher = "出版社",
        publishedYear = "2024",
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        location = "本棚A",
        readingStatus = ReadingStatus.READING,
    )

    private fun sampleBook(title: String) = LibraryBook(
        copyId = "copy-1",
        workId = "work-1",
        editionId = "edition-1",
        title = title,
        primaryAuthor = "著者",
        isbn13 = "9784820418078",
        publisher = "出版社",
        publishedYear = 2024,
        coverUrl = null,
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        classificationSource = ClassificationSource.NDL,
        mediaType = MediaType.PHYSICAL,
        location = "本棚A",
        readingStatus = ReadingStatus.READING,
        addedAt = 1_700_000_000_000L,
    )

    private class FakeRepository : LibraryRepository {
        private val books = MutableStateFlow(emptyList<LibraryBook>())
        lateinit var updateResult: UpdateBookResult
        var restoreResult = false
        var restoredSnapshots: Pair<LibraryBook, LibraryBook>? = null

        override fun observeLibrary(): Flow<List<LibraryBook>> = books

        override suspend fun addFromIsbn(rawIsbn: String): AddBookResult = error("Not used")

        override suspend fun updateBook(copyId: String, draft: BookEditDraft): UpdateBookResult =
            updateResult

        override suspend fun restoreBook(
            previous: LibraryBook,
            expectedCurrent: LibraryBook,
        ): Boolean {
            restoredSnapshots = previous to expectedCurrent
            return restoreResult
        }

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
}
