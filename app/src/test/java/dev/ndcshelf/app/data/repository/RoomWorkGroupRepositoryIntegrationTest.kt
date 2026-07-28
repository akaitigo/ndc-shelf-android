package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.domain.model.WorkVariantSuggestionConfidence
import dev.ndcshelf.app.domain.repository.WorkGroupMutationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomWorkGroupRepositoryIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun suggestionsAreTransientAndLinkPreservesEditionMetadataAndCopies() = runBlocking {
        seedVariants()
        val ids = ArrayDeque(listOf("group", "member-source", "member-paperback"))
        val repository = RoomWorkGroupRepository(
            database,
            idFactory = { ids.removeFirst() },
            nowMillis = { 100 },
        )

        val editor = requireNotNull(repository.editorFor("source"))

        assertEquals(listOf("paperback"), editor.suggestions.map { it.work.workId })
        assertEquals(WorkVariantSuggestionConfidence.HIGH, editor.suggestions.single().confidence)
        assertTrue(database.workGroupDao().getAllGroups().isEmpty())
        assertTrue(database.workGroupDao().getAllMemberships().isEmpty())

        val result = repository.link(
            "source",
            "paperback",
            "銀河鉄道の夜 新装版",
            "銀河鉄道の夜（文庫版）",
            seriesSubstitutionEnabled = true,
        )

        assertEquals(WorkGroupMutationResult.Linked("group"), result)
        assertEquals(2, database.workGroupDao().getAllMemberships().size)
        assertTrue(requireNotNull(database.workGroupDao().findGroupById("group")).seriesSubstitutionEnabled)
        assertEquals(listOf("other", "paperback", "source"), database.libraryDao().getAllWorks().map { it.id })
        val editions = database.libraryDao().getAllEditions()
        assertEquals(listOf("9784000000022", "9784000000015"), editions.mapNotNull { it.isbn13 })
        assertEquals(listOf("出版社B", "出版社A"), editions.mapNotNull { it.publisher })
        assertEquals(listOf(2024, 2020), editions.mapNotNull { it.publishedYear })
        assertEquals(listOf("913.6", "913.6"), editions.mapNotNull { it.ndcCode })
        assertEquals(2, database.libraryDao().getAllCopies().size)
    }

    @Test
    fun unlinkDeletesOnlyRelationAndLastTwoMembershipsCollapseGroup() = runBlocking {
        seedVariants()
        val ids = ArrayDeque(listOf("group", "member-source", "member-paperback"))
        val repository = RoomWorkGroupRepository(database, { ids.removeFirst() }, { 100 })
        repository.link(
            "source",
            "paperback",
            "銀河鉄道の夜 新装版",
            "銀河鉄道の夜（文庫版）",
            false,
        )

        assertEquals(WorkGroupMutationResult.Unlinked, repository.unlink("member-paperback"))

        assertTrue(database.workGroupDao().getAllGroups().isEmpty())
        assertTrue(database.workGroupDao().getAllMemberships().isEmpty())
        assertEquals(3, database.libraryDao().getAllWorks().size)
        assertEquals(2, database.libraryDao().getAllEditions().size)
        assertEquals(2, database.libraryDao().getAllCopies().size)
    }

    @Test
    fun staleTitleAndTwoExistingGroupsFailClosed() = runBlocking {
        seedVariants()
        val ids = ArrayDeque(
            listOf(
                "group-a", "member-source", "member-paperback",
                "group-b", "member-other", "member-fourth",
            ),
        )
        database.libraryDao().insertWork(BookWorkEntity("fourth", "別作品 電子版", "別著者"))
        val repository = RoomWorkGroupRepository(database, { ids.removeFirst() }, { 100 })

        assertEquals(
            WorkGroupMutationResult.Conflict,
            repository.link("source", "paperback", "古い題名", "銀河鉄道の夜（文庫版）", false),
        )
        assertTrue(database.workGroupDao().getAllGroups().isEmpty())
        repository.link(
            "source",
            "paperback",
            "銀河鉄道の夜 新装版",
            "銀河鉄道の夜（文庫版）",
            false,
        )
        repository.link("other", "fourth", "別作品", "別作品 電子版", false)

        assertEquals(
            WorkGroupMutationResult.Conflict,
            repository.link("source", "other", "銀河鉄道の夜 新装版", "別作品", false),
        )
        assertEquals(2, database.workGroupDao().getAllGroups().size)
        assertEquals(4, database.workGroupDao().getAllMemberships().size)
        assertNull(repository.editorFor("missing"))
    }

    private suspend fun seedVariants() {
        database.libraryDao().upsertWorks(
            listOf(
                BookWorkEntity("source", "銀河鉄道の夜 新装版", "宮沢賢治"),
                BookWorkEntity("paperback", "銀河鉄道の夜（文庫版）", "宮沢 賢治"),
                BookWorkEntity("other", "別作品", "別著者"),
            ),
        )
        database.libraryDao().upsertEditions(
            listOf(
                edition("edition-source", "source", "9784000000015", "出版社A", 2020, "cover-a"),
                edition("edition-paperback", "paperback", "9784000000022", "出版社B", 2024, null),
            ),
        )
        database.libraryDao().upsertCopies(
            listOf(
                copy("copy-source", "edition-source", "PHYSICAL"),
                copy("copy-paperback", "edition-paperback", "DIGITAL"),
            ),
        )
    }

    private fun edition(
        id: String,
        workId: String,
        isbn: String,
        publisher: String,
        year: Int,
        cover: String?,
    ) = BookEditionEntity(
        id, workId, isbn, publisher, year, cover, "913.6", "NDC10", "NDL", "NDL",
    )

    private fun copy(id: String, editionId: String, mediaType: String) = OwnedCopyEntity(
        id, editionId, mediaType, "未設定", "UNREAD", 10,
    )
}
