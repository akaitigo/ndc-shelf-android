package dev.ndcshelf.app

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.insights.InMemoryInsightsExclusionStore
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingSession
import dev.ndcshelf.app.domain.model.ReadingSessionDraft
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.AddReadingSessionResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.DeleteReadingSessionResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.ReadingHistoryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.RestoreReadingSessionResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.domain.repository.UpdateReadingSessionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
class InsightsViewModelTest {
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
    fun stateExposesInsightsComputedOnDeviceData() =
        runTest(dispatcher) {
            val books = MutableStateFlow(listOf(book("copy-1", addedAt = NOW - days(420))))
            val viewModel = viewModel(books = books)
            backgroundScope.launch { viewModel.state.collect { } }

            val ready = viewModel.state.value as InsightsUiState.Ready

            assertEquals(1, ready.insights.totalCount)
            assertEquals(1, ready.insights.tsundoku.unreadCount)
            assertEquals(
                listOf("copy-1" to 420L),
                ready.insights.tsundoku.longestUnread
                    .map { it.book.copyId to it.daysSinceAdded },
            )
        }

    @Test
    fun excludeBookRemovesCandidateAndResetRestoresIt() =
        runTest(dispatcher) {
            val books =
                MutableStateFlow(
                    listOf(
                        book("copy-1", addedAt = NOW - days(100)),
                        book("copy-2", addedAt = NOW - days(50)),
                    ),
                )
            val viewModel = viewModel(books = books)
            backgroundScope.launch { viewModel.state.collect { } }

            viewModel.excludeBook("copy-1")

            val afterExclude = viewModel.state.value as InsightsUiState.Ready
            assertEquals(
                listOf("copy-2"),
                afterExclude.insights.tsundoku.longestUnread
                    .map { it.book.copyId },
            )
            assertEquals(1, afterExclude.insights.excludedCount)

            viewModel.resetExclusions()

            val afterReset = viewModel.state.value as InsightsUiState.Ready
            assertEquals(
                listOf("copy-1", "copy-2"),
                afterReset.insights.tsundoku.longestUnread
                    .map { it.book.copyId },
            )
            assertEquals(0, afterReset.insights.excludedCount)
        }

    @Test
    fun libraryChangesRecomputeInsights() =
        runTest(dispatcher) {
            val books = MutableStateFlow(listOf(book("copy-1", addedAt = NOW - days(10))))
            val viewModel = viewModel(books = books)
            backgroundScope.launch { viewModel.state.collect { } }

            books.value = emptyList()

            val ready = viewModel.state.value as InsightsUiState.Ready
            assertEquals(0, ready.insights.totalCount)
            assertTrue(
                ready.insights.tsundoku.longestUnread
                    .isEmpty(),
            )
            assertTrue(ready.insights.rediscoveries.isEmpty())
        }

    private fun viewModel(
        books: MutableStateFlow<List<LibraryBook>>,
        sessions: MutableStateFlow<List<ReadingSession>> = MutableStateFlow(emptyList()),
    ): InsightsViewModel =
        InsightsViewModel(
            libraryRepository = FakeInsightsLibraryRepository(books),
            readingHistoryRepository = FakeInsightsReadingHistoryRepository(sessions),
            exclusionStore = InMemoryInsightsExclusionStore(),
            nowMillis = { NOW },
            rediscoverySeed = 42L,
        )

    private fun days(count: Long): Long = count * 24 * 60 * 60 * 1000

    private fun book(
        copyId: String,
        addedAt: Long,
    ): LibraryBook =
        LibraryBook(
            copyId = copyId,
            workId = "work-$copyId",
            editionId = "edition-$copyId",
            title = "タイトル$copyId",
            primaryAuthor = "著者",
            isbn13 = null,
            publisher = null,
            publishedYear = null,
            coverUrl = null,
            ndcCode = null,
            ndcEdition = null,
            classificationSource = ClassificationSource.MANUAL,
            mediaType = MediaType.PHYSICAL,
            location = "本棚",
            readingStatus = ReadingStatus.UNREAD,
            addedAt = addedAt,
        )

    private companion object {
        const val NOW = 1_753_000_000_000L
    }
}

private class FakeInsightsLibraryRepository(
    private val books: MutableStateFlow<List<LibraryBook>>,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryBook>> = books

    override suspend fun addFromIsbn(rawIsbn: String): AddBookResult = AddBookResult.Failure(AddBookFailure.SAVE)

    override suspend fun updateBook(
        copyId: String,
        draft: BookEditDraft,
    ): UpdateBookResult = UpdateBookResult.NotFound

    override suspend fun restoreBook(
        previous: LibraryBook,
        expectedCurrent: LibraryBook,
    ): Boolean = false

    override suspend fun deleteBook(copyId: String): DeleteBookResult = DeleteBookResult.NotFound

    override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult = RestoreDeletedBookResult.Failure

    override suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult = error("Not used in insights tests")

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = error("Not used in insights tests")
}

private class FakeInsightsReadingHistoryRepository(
    private val sessions: MutableStateFlow<List<ReadingSession>>,
) : ReadingHistoryRepository {
    override fun observeSessionsForEdition(editionId: String): Flow<List<ReadingSession>> = sessions

    override fun observeAllSessions(): Flow<List<ReadingSession>> = sessions

    override suspend fun addSession(
        copyId: String,
        draft: ReadingSessionDraft,
    ): AddReadingSessionResult = AddReadingSessionResult.Failure

    override suspend fun updateSession(
        sessionId: String,
        draft: ReadingSessionDraft,
    ): UpdateReadingSessionResult = UpdateReadingSessionResult.Failure

    override suspend fun deleteSession(sessionId: String): DeleteReadingSessionResult = DeleteReadingSessionResult.Failure

    override suspend fun restoreSession(session: ReadingSession): RestoreReadingSessionResult = RestoreReadingSessionResult.Failure
}
