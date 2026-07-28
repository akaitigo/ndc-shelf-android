package dev.ndcshelf.app

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelLibrarySearchTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun debounceCancelsOldQueryAndPublishesMatchingCriteriaWithResults() = runTest(dispatcher) {
        val repository = SearchRepository()
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)
        val collection = backgroundScope.launch { viewModel.librarySearchResult.collect() }

        advanceTimeBy(250)
        runCurrent()
        viewModel.updateLibraryQuery("old")
        advanceTimeBy(250)
        runCurrent()
        assertTrue("old" in repository.startedQueries)

        viewModel.updateLibraryQuery("new")
        advanceTimeBy(250)
        runCurrent()

        assertEquals("new", viewModel.librarySearchResult.value.criteria.query)
        assertEquals("new", viewModel.librarySearchResult.value.books.single().title)
        assertTrue("old" in repository.cancelledQueries)
        collection.cancel()
    }

    private class SearchRepository : LibraryRepository {
        val startedQueries = mutableListOf<String>()
        val cancelledQueries = mutableListOf<String>()
        private val books = MutableStateFlow(emptyList<LibraryBook>())

        override fun observeLibrary(): Flow<List<LibraryBook>> = books

        override fun observeLibrary(criteria: LibrarySearchCriteria): Flow<List<LibraryBook>> = flow {
            val query = criteria.query
            startedQueries += query
            try {
                if (query == "old") delay(1_000)
                emit(listOf(book(query)))
            } finally {
                if (query == "old") cancelledQueries += query
            }
        }

        override suspend fun addFromIsbn(rawIsbn: String): AddBookResult =
            AddBookResult.Failure(AddBookFailure.SAVE)

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
        fun book(title: String) = LibraryBook(
            copyId = "copy-$title",
            workId = "work-$title",
            editionId = "edition-$title",
            title = title,
            primaryAuthor = "著者",
            isbn13 = null,
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
    }
}
