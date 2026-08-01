package dev.ndcshelf.app

import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySearchSettingsStore
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.SavedSearch
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagAssignment
import dev.ndcshelf.app.domain.model.TagColorRole
import dev.ndcshelf.app.domain.model.TagWithUsage
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.SavedSearchMutationResult
import dev.ndcshelf.app.domain.repository.TagAssignmentResult
import dev.ndcshelf.app.domain.repository.TagMutationResult
import dev.ndcshelf.app.domain.repository.TagRepository
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.domain.search.SearchInterpretationChip
import dev.ndcshelf.app.domain.text.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * 自然言語検索のViewModel統合テスト。
 * 解釈はdebounce後に1回だけ実行され、チップ解除・入力クリアで再検索されることを検証する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelNaturalLanguageSearchTest {
    private val dispatcher = StandardTestDispatcher()

    /** fixtureと同じ基準時刻: 2026-07-20T12:00:00+09:00 */
    private val fixedNowMillis = 1_784_516_400_000L
    private val timeZone = TimeZone.getTimeZone("Asia/Tokyo")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repository: RecordingRepository,
        tags: List<Tag> = emptyList(),
        settings: LibrarySearchSettingsStore = RecordingSettingsStore(),
    ) = MainViewModel(
        repository = repository,
        importIoDispatcher = dispatcher,
        importComputationDispatcher = dispatcher,
        tagRepository = FakeTagRepository(tags),
        librarySearchSettings = settings,
        nowMillisProvider = { fixedNowMillis },
        timeZoneProvider = { timeZone },
    )

    @Test
    fun interpretsQueryOnceAfterDebounceIntoEffectiveCriteriaAndChips() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val viewModel = viewModel(repository)
            val collection = backgroundScope.launch { viewModel.librarySearchResult.collect() }
            advanceTimeBy(250)
            runCurrent()
            repository.searchedCriteria.clear()

            // 打鍵ごとには検索せず、debounce確定後の1回だけ解釈・検索する。
            viewModel.updateLibraryQuery("未読")
            advanceTimeBy(100)
            viewModel.updateLibraryQuery("未読の自然")
            advanceTimeBy(100)
            viewModel.updateLibraryQuery("未読の自然科学")
            advanceTimeBy(250)
            runCurrent()

            assertEquals(1, repository.searchedCriteria.size)
            val effective = repository.searchedCriteria.single()
            assertEquals("", effective.query)
            assertEquals(ReadingStatus.UNREAD, effective.readingStatus)
            assertEquals(4, effective.ndcTopClass)

            val result = viewModel.librarySearchResult.value
            assertEquals("未読の自然科学", result.criteria.query)
            assertEquals(
                listOf(
                    UiMessage(R.string.reading_status_unread),
                    UiMessage(R.string.nl_search_chip_ndc, 4, UiMessage(R.string.ndc_category_4)),
                ),
                result.interpretationChips.map(SearchInterpretationChip::label),
            )
            collection.cancel()
        }

    @Test
    fun dismissingChipRemovesConditionRestoresTokenAndTriggersResearch() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val viewModel = viewModel(repository)
            val collection = backgroundScope.launch { viewModel.librarySearchResult.collect() }
            viewModel.updateLibraryQuery("未読の自然科学")
            advanceTimeBy(250)
            runCurrent()
            val statusChip =
                viewModel.librarySearchResult.value.interpretationChips
                    .first { chip -> chip is SearchInterpretationChip.Status }
            repository.searchedCriteria.clear()

            viewModel.dismissInterpretationChip(statusChip.id)
            runCurrent()

            val effective = repository.searchedCriteria.single()
            assertNull(effective.readingStatus)
            assertEquals(4, effective.ndcTopClass)
            assertEquals("未読", effective.query)
            assertEquals(
                listOf(
                    UiMessage(
                        R.string.nl_search_chip_ndc,
                        4,
                        UiMessage(R.string.ndc_category_4),
                    ),
                ),
                viewModel.librarySearchResult.value.interpretationChips
                    .map(SearchInterpretationChip::label),
            )
            collection.cancel()
        }

    @Test
    fun clearingQueryRemovesEveryInterpretedConditionAndResetsDismissals() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val viewModel = viewModel(repository)
            val collection = backgroundScope.launch { viewModel.librarySearchResult.collect() }
            viewModel.updateLibraryQuery("書斎にある未読の本")
            advanceTimeBy(250)
            runCurrent()
            assertEquals(2, viewModel.librarySearchResult.value.interpretationChips.size)
            viewModel.dismissInterpretationChip(
                viewModel.librarySearchResult.value.interpretationChips
                    .first()
                    .id,
            )
            runCurrent()

            viewModel.updateLibraryQuery("")
            advanceTimeBy(250)
            runCurrent()

            val effective = repository.searchedCriteria.last()
            assertEquals(LibrarySearchCriteria(), effective)
            assertTrue(
                viewModel.librarySearchResult.value.interpretationChips
                    .isEmpty(),
            )

            // クエリ変更で解除状態がリセットされ、同じ入力で再びすべてのチップが出る。
            viewModel.updateLibraryQuery("書斎にある未読の本")
            advanceTimeBy(250)
            runCurrent()
            assertEquals(2, viewModel.librarySearchResult.value.interpretationChips.size)
            collection.cancel()
        }

    @Test
    fun bareKeywordBeatsTagNameWhileExplicitTagSyntaxSelectsTag() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val unreadTag = Tag("tag-unread", "未読", TagColorRole.GRAY, 0L, 0L)
            val viewModel = viewModel(repository, tags = listOf(unreadTag))
            val collection = backgroundScope.launch { viewModel.librarySearchResult.collect() }

            viewModel.updateLibraryQuery("未読")
            advanceTimeBy(250)
            runCurrent()
            val bare = repository.searchedCriteria.last()
            assertEquals(ReadingStatus.UNREAD, bare.readingStatus)
            assertTrue(bare.tagIds.isEmpty())

            viewModel.updateLibraryQuery("#未読")
            advanceTimeBy(250)
            runCurrent()
            val explicit = repository.searchedCriteria.last()
            assertNull(explicit.readingStatus)
            assertEquals(setOf("tag-unread"), explicit.tagIds)
            assertEquals(
                listOf(UiMessage(R.string.nl_search_chip_tag, "未読")),
                viewModel.librarySearchResult.value.interpretationChips
                    .map(SearchInterpretationChip::label),
            )
            collection.cancel()
        }

    @Test
    fun uninterpretableQueryFallsBackToPlainSearchWithoutChips() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val viewModel = viewModel(repository)
            val collection = backgroundScope.launch { viewModel.librarySearchResult.collect() }

            viewModel.updateLibraryQuery("面白い本")
            advanceTimeBy(250)
            runCurrent()

            val effective = repository.searchedCriteria.last()
            assertEquals("面白い本", effective.query)
            assertNull(effective.readingStatus)
            assertNull(effective.ndcTopClass)
            assertTrue(
                viewModel.librarySearchResult.value.interpretationChips
                    .isEmpty(),
            )
            collection.cancel()
        }

    @Test
    fun persistedCriteriaKeepRawQueryWithoutInterpretedFields() =
        runTest(dispatcher) {
            val repository = RecordingRepository()
            val settings = RecordingSettingsStore()
            val viewModel = viewModel(repository, settings = settings)
            val collection = backgroundScope.launch { viewModel.librarySearchResult.collect() }

            viewModel.updateLibraryQuery("未読の自然科学")
            advanceTimeBy(250)
            runCurrent()

            val saved = requireNotNull(settings.lastSaved)
            assertEquals("未読の自然科学", saved.query)
            assertNull(saved.readingStatus)
            assertNull(saved.ndcTopClass)
            assertNull(saved.locationQuery)
            assertNull(saved.addedAfterMillis)
            assertNull(saved.addedBeforeMillis)
            collection.cancel()
        }

    private class RecordingSettingsStore : LibrarySearchSettingsStore {
        var lastSaved: LibrarySearchCriteria? = null

        override fun load(): LibrarySearchCriteria = LibrarySearchCriteria()

        override fun save(criteria: LibrarySearchCriteria) {
            lastSaved = criteria
        }
    }

    private class RecordingRepository : LibraryRepository {
        val searchedCriteria = mutableListOf<LibrarySearchCriteria>()
        private val books = MutableStateFlow(emptyList<LibraryBook>())

        override fun observeLibrary(): Flow<List<LibraryBook>> = books

        override fun observeLibrary(criteria: LibrarySearchCriteria): Flow<List<LibraryBook>> {
            searchedCriteria += criteria
            return books
        }

        override suspend fun addFromIsbn(rawIsbn: String): AddBookResult = AddBookResult.Failure(AddBookFailure.SAVE)

        override suspend fun updateBook(
            copyId: String,
            draft: BookEditDraft,
        ): UpdateBookResult = error("Not used")

        override suspend fun restoreBook(
            previous: LibraryBook,
            expectedCurrent: LibraryBook,
        ): Boolean = error("Not used")

        override suspend fun deleteBook(copyId: String): DeleteBookResult = error("Not used")

        override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult = error("Not used")

        override suspend fun previewImport(
            batch: LibraryImportBatch,
            conflictPolicy: ImportConflictPolicy,
        ): ImportPreviewResult = error("Not used")

        override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = error("Not used")
    }

    private class FakeTagRepository(
        tags: List<Tag>,
    ) : TagRepository {
        private val tagsFlow =
            MutableStateFlow(tags.map { tag -> TagWithUsage(tag, taggedWorkCount = 0) })

        override fun observeTags(): Flow<List<TagWithUsage>> = tagsFlow

        override fun observeAssignments(): Flow<List<TagAssignment>> = flowOf(emptyList())

        override fun observeSavedSearches(): Flow<List<SavedSearch>> = flowOf(emptyList())

        override suspend fun getTagsSnapshot(): List<Tag> = tagsFlow.value.map(TagWithUsage::tag)

        override suspend fun getAssignmentsSnapshot(): List<TagAssignment> = emptyList()

        override suspend fun createTag(
            name: String,
            colorRole: TagColorRole,
        ): TagMutationResult = error("Not used")

        override suspend fun updateTag(
            tagId: String,
            name: String,
            colorRole: TagColorRole,
        ): TagMutationResult = error("Not used")

        override suspend fun mergeTags(
            sourceTagId: String,
            targetTagId: String,
        ): TagMutationResult = error("Not used")

        override suspend fun deleteTag(tagId: String): TagMutationResult = error("Not used")

        override suspend fun setTagOnWorks(
            tagId: String,
            workIds: Set<String>,
            assigned: Boolean,
        ): TagAssignmentResult = error("Not used")

        override suspend fun saveSearch(
            name: String,
            criteria: LibrarySearchCriteria,
        ): SavedSearchMutationResult = error("Not used")

        override suspend fun renameSavedSearch(
            searchId: String,
            name: String,
        ): SavedSearchMutationResult = error("Not used")

        override suspend fun deleteSavedSearch(searchId: String): SavedSearchMutationResult = error("Not used")
    }
}
