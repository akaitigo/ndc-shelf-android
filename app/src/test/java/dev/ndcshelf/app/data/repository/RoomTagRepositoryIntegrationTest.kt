package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.toSQLiteQuery
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.data.sync.RoomSyncDomainStore
import dev.ndcshelf.app.data.sync.RoomSyncEngine
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.TagColorRole
import dev.ndcshelf.app.domain.model.TagNameRules
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.SavedSearchMutationResult
import dev.ndcshelf.app.domain.repository.TagAssignmentResult
import dev.ndcshelf.app.domain.repository.TagMutationResult
import dev.ndcshelf.app.domain.sync.SyncMutation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomTagRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private var now = 1_800_000_000_000L
    private var nextId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun createNormalizesNamesAndRejectsDuplicatesAndInvalidInput() =
        runBlocking {
            val repository = repository()

            val created =
                repository.createTag("  SF   ハード  ", TagColorRole.BLUE)
                    as TagMutationResult.Done
            assertEquals("SF ハード", requireNotNull(created.tag).name)

            assertSame(
                TagMutationResult.Duplicate,
                repository.createTag("SF ハード", TagColorRole.RED),
            )
            assertTrue(repository.createTag("   ", TagColorRole.RED) is TagMutationResult.Invalid)
            assertTrue(
                repository.createTag(
                    "あ".repeat(TagNameRules.MAX_NAME_LENGTH + 1),
                    TagColorRole.RED,
                ) is TagMutationResult.Invalid,
            )
            assertTrue(
                repository.createTag("制御\u0000文字", TagColorRole.RED) is TagMutationResult.Invalid,
            )
        }

    @Test
    fun tagCountLimitIsEnforcedAtTheBoundary() =
        runBlocking {
            val repository = repository()

            // 上限値ちょうどまで作成できる（大量タグでの動作確認）。
            repeat(TagNameRules.MAX_TAGS) { index ->
                val result = repository.createTag("タグ$index", TagColorRole.GRAY)
                assertTrue("tag $index should be created: $result", result is TagMutationResult.Done)
            }
            assertEquals(TagNameRules.MAX_TAGS, database.tagDao().countTags())

            assertSame(
                TagMutationResult.LimitReached,
                repository.createTag("上限超過", TagColorRole.GRAY),
            )
            assertEquals(
                TagNameRules.MAX_TAGS,
                repository.observeTags().first().size,
            )
        }

    @Test
    fun bulkAssignUnassignDeduplicatesAndFiltersLibrarySearch() =
        runBlocking {
            insertBook("a")
            insertBook("b")
            val repository = repository()
            val tag =
                requireNotNull(
                    (repository.createTag("積読", TagColorRole.ORANGE) as TagMutationResult.Done).tag,
                )

            val assigned = repository.setTagOnWorks(tag.id, setOf("work-a", "work-b"), assigned = true)
            assertEquals(2, (assigned as TagAssignmentResult.Applied).changedCount)
            // 再付与は冪等（重複付与を1件へ正規化）。
            val reassigned = repository.setTagOnWorks(tag.id, setOf("work-a"), assigned = true)
            assertEquals(0, (reassigned as TagAssignmentResult.Applied).changedCount)
            assertEquals(2, database.tagDao().getAllAssignments().size)

            // タグ条件のAND検索が該当作品だけを返す。
            val second =
                requireNotNull(
                    (repository.createTag("SF", TagColorRole.BLUE) as TagMutationResult.Done).tag,
                )
            repository.setTagOnWorks(second.id, setOf("work-a"), assigned = true)
            val singleTagRows =
                database
                    .libraryDao()
                    .observeLibrarySearch(LibrarySearchCriteria(tagIds = setOf(tag.id)).toSQLiteQuery())
                    .first()
            assertEquals(setOf("work-a", "work-b"), singleTagRows.mapTo(hashSetOf()) { it.workId })
            val bothTagRows =
                database
                    .libraryDao()
                    .observeLibrarySearch(
                        LibrarySearchCriteria(tagIds = setOf(tag.id, second.id)).toSQLiteQuery(),
                    ).first()
            assertEquals(listOf("work-a"), bothTagRows.map { it.workId })

            val unassigned = repository.setTagOnWorks(tag.id, setOf("work-a", "work-b"), assigned = false)
            assertEquals(2, (unassigned as TagAssignmentResult.Applied).changedCount)
            assertEquals(1, database.tagDao().getAllAssignments().size)
            assertSame(
                TagAssignmentResult.NotFound,
                repository.setTagOnWorks("missing-tag", setOf("work-a"), true),
            )
        }

    @Test
    fun mergeMovesAssignmentsWithoutDuplicatesAndDeletesTheSource() =
        runBlocking {
            insertBook("a")
            insertBook("b")
            val engine = RoomSyncEngine(database, RoomSyncDomainStore(database))
            engine.initializeDevice("device-a")
            val repository = repository(engine)
            val source =
                requireNotNull(
                    (repository.createTag("SF小説", TagColorRole.BLUE) as TagMutationResult.Done).tag,
                )
            val target =
                requireNotNull(
                    (repository.createTag("SF", TagColorRole.TEAL) as TagMutationResult.Done).tag,
                )
            repository.setTagOnWorks(source.id, setOf("work-a", "work-b"), true)
            repository.setTagOnWorks(target.id, setOf("work-a"), true)

            val merged = repository.mergeTags(source.id, target.id)

            assertTrue(merged is TagMutationResult.Done)
            assertEquals(null, database.tagDao().findTagById(source.id))
            val assignments = database.tagDao().getAllAssignments()
            assertEquals(setOf("work-a", "work-b"), assignments.mapTo(hashSetOf()) { it.workId })
            assertTrue(assignments.all { it.tagId == target.id })
            val mutations = engine.pendingOperations().map { it.mutation }
            assertTrue(
                mutations.any { it is SyncMutation.Delete && it.entityType == "tag" && it.entityId == source.id },
            )
            assertTrue(
                mutations.any { it is SyncMutation.Upsert && it.entityType == "tagAssignment" },
            )
            assertTrue(
                repository.mergeTags(target.id, target.id) is TagMutationResult.Invalid,
            )
        }

    @Test
    fun deleteJournalsAssignmentDeletesAndRenameRejectsDuplicates() =
        runBlocking {
            insertBook("a")
            val engine = RoomSyncEngine(database, RoomSyncDomainStore(database))
            engine.initializeDevice("device-a")
            val repository = repository(engine)
            val tag =
                requireNotNull(
                    (repository.createTag("削除対象", TagColorRole.RED) as TagMutationResult.Done).tag,
                )
            val other =
                requireNotNull(
                    (repository.createTag("残すタグ", TagColorRole.GREEN) as TagMutationResult.Done).tag,
                )
            repository.setTagOnWorks(tag.id, setOf("work-a"), true)
            val assignmentId =
                database
                    .tagDao()
                    .getAllAssignments()
                    .single()
                    .id

            assertSame(
                TagMutationResult.Duplicate,
                repository.updateTag(tag.id, "残すタグ", TagColorRole.RED),
            )
            val renamed =
                repository.updateTag(tag.id, "改名済み", TagColorRole.PURPLE)
                    as TagMutationResult.Done
            assertEquals("改名済み", requireNotNull(renamed.tag).name)
            assertEquals(TagColorRole.PURPLE, requireNotNull(renamed.tag).colorRole)

            val deleted = repository.deleteTag(tag.id)
            assertTrue(deleted is TagMutationResult.Done)
            assertEquals(0, database.tagDao().getAllAssignments().size)
            val mutations = engine.pendingOperations().map { it.mutation }
            assertTrue(
                mutations.any {
                    it is SyncMutation.Delete && it.entityType == "tagAssignment" &&
                        it.entityId == assignmentId
                },
            )
            assertTrue(
                mutations.any { it is SyncMutation.Delete && it.entityType == "tag" && it.entityId == tag.id },
            )
            assertSame(TagMutationResult.NotFound, repository.deleteTag(tag.id))
            assertEquals("残すタグ", requireNotNull(database.tagDao().findTagById(other.id)).name)
        }

    @Test
    fun savedSearchesRoundTripCriteriaAndEnforceLimitsAndDuplicates() =
        runBlocking {
            val repository = repository()
            val criteria =
                LibrarySearchCriteria(
                    query = "夏目",
                    readingStatus = ReadingStatus.READING,
                    sort = LibrarySort.TITLE,
                    tagIds = setOf("tag-1"),
                    selectedEditionId = "edition-should-not-persist",
                )

            val saved =
                repository.saveSearch("  読書中の夏目  ", criteria)
                    as SavedSearchMutationResult.Done
            val savedSearch = requireNotNull(saved.savedSearch)
            assertEquals("読書中の夏目", savedSearch.name)
            assertEquals("夏目", savedSearch.criteria.query)
            assertEquals(ReadingStatus.READING, savedSearch.criteria.readingStatus)
            assertEquals(LibrarySort.TITLE, savedSearch.criteria.sort)
            assertEquals(setOf("tag-1"), savedSearch.criteria.tagIds)
            // 一時状態（詳細表示中のEdition）は保存しない。
            assertEquals(null, savedSearch.criteria.selectedEditionId)

            assertSame(
                SavedSearchMutationResult.Duplicate,
                repository.saveSearch("読書中の夏目", criteria),
            )
            val renamed = repository.renameSavedSearch(savedSearch.id, "改名した検索")
            assertTrue(renamed is SavedSearchMutationResult.Done)
            assertEquals(
                "改名した検索",
                repository
                    .observeSavedSearches()
                    .first()
                    .single()
                    .name,
            )
            assertTrue(
                repository.deleteSavedSearch(savedSearch.id) is SavedSearchMutationResult.Done,
            )
            assertSame(
                SavedSearchMutationResult.NotFound,
                repository.deleteSavedSearch(savedSearch.id),
            )

            repeat(TagNameRules.MAX_SAVED_SEARCHES) { index ->
                assertTrue(
                    repository.saveSearch("検索$index", LibrarySearchCriteria())
                        is SavedSearchMutationResult.Done,
                )
            }
            assertSame(
                SavedSearchMutationResult.LimitReached,
                repository.saveSearch("上限超過", LibrarySearchCriteria()),
            )
        }

    @Test
    fun deletingTheLastCopyJournalsTagAssignmentDeletes() =
        runBlocking {
            insertBook("a")
            val engine = RoomSyncEngine(database, RoomSyncDomainStore(database))
            engine.initializeDevice("device-a")
            val tagRepository = repository(engine)
            val libraryRepository =
                DefaultLibraryRepository(
                    database = database,
                    metadataService = BookMetadataService { BookMetadataLookupResult.NotFound },
                    idFactory = { "library-${nextId++}" },
                    nowMillis = { now++ },
                    syncJournal = engine,
                )
            val tag =
                requireNotNull(
                    (tagRepository.createTag("消える作品のタグ", TagColorRole.BROWN) as TagMutationResult.Done).tag,
                )
            tagRepository.setTagOnWorks(tag.id, setOf("work-a"), true)
            val assignmentId =
                database
                    .tagDao()
                    .getAllAssignments()
                    .single()
                    .id

            val deleted = libraryRepository.deleteBook("copy-a")

            assertTrue(deleted.toString(), deleted is DeleteBookResult.Deleted)
            assertEquals(0, database.tagDao().getAllAssignments().size)
            // タグ自体は残る（付与だけが消える）。
            assertEquals(1, database.tagDao().countTags())
            assertTrue(
                engine.pendingOperations().map { it.mutation }.any {
                    it is SyncMutation.Delete && it.entityType == "tagAssignment" &&
                        it.entityId == assignmentId
                },
            )
        }

    private fun repository(
        syncJournal: dev.ndcshelf.app.domain.sync.SyncMutationJournal =
            dev.ndcshelf.app.domain.sync.SyncMutationJournal.Disabled,
    ) = RoomTagRepository(
        database = database,
        idFactory = { "tag-id-${nextId++}" },
        nowMillis = { now++ },
        syncJournal = syncJournal,
    )

    private suspend fun insertBook(suffix: String) {
        database.libraryDao().insertWork(BookWorkEntity("work-$suffix", "題名$suffix", "著者"))
        database.libraryDao().insertEdition(
            BookEditionEntity(
                id = "edition-$suffix",
                workId = "work-$suffix",
                isbn13 = null,
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = "UNKNOWN",
                bibliographicSource = "MANUAL",
            ),
        )
        database.libraryDao().insertCopy(
            OwnedCopyEntity(
                id = "copy-$suffix",
                editionId = "edition-$suffix",
                mediaType = "PHYSICAL",
                location = "未設定",
                readingStatus = "UNREAD",
                addedAt = 1,
            ),
        )
    }
}
