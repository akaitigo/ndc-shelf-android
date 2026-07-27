package dev.ndcshelf.app.release

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.SeriesReleaseSource
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceCandidate
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceFailure
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceResult
import dev.ndcshelf.app.data.repository.RoomSeriesRepository
import dev.ndcshelf.app.data.repository.RoomSeriesWatchRepository
import dev.ndcshelf.app.data.repository.RoomWorkGroupRepository
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.repository.SeriesConfirmationDraft
import dev.ndcshelf.app.domain.repository.SeriesConfirmationResult
import dev.ndcshelf.app.domain.repository.SeriesConfirmationTarget
import dev.ndcshelf.app.domain.repository.SeriesWatchCheckResult
import dev.ndcshelf.app.domain.repository.SeriesWatchMutationResult
import dev.ndcshelf.app.domain.repository.WorkGroupMutationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V04SeriesReleaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun anonymousFixtureKeepsCandidateFactsConservativeAndSeriesMembershipReversible() = runBlocking {
        seedAnonymousWorks()
        val ids = ArrayDeque(
            listOf(
                "series", "member-1", "member-3", "member-upper",
                "member-lower", "member-side", "member-omnibus", "member-3-restored",
            ),
        )
        val repository = RoomSeriesRepository(database, idFactory = { ids.removeFirst() }, nowMillis = { 100 })
        val drafts = listOf(
            draft("integer-1", "1巻", SeriesMembershipType.MAIN_STORY),
            draft("integer-3", "3巻", SeriesMembershipType.MAIN_STORY),
            draft("upper", "上巻", SeriesMembershipType.MAIN_STORY),
            draft("lower", "下巻", SeriesMembershipType.MAIN_STORY),
            draft("side", "外伝", SeriesMembershipType.SIDE_STORY),
            draft("omnibus", "1-2巻", SeriesMembershipType.OMNIBUS),
        )

        val confirmed = repository.confirm(SeriesConfirmationTarget.New("匿名年代記"), drafts)
        assertTrue(confirmed is SeriesConfirmationResult.Confirmed)
        val overview = repository.observeCatalog().first().single()
        assertEquals(6, overview.knownVolumeCount)
        assertEquals(
            listOf("3巻", "上巻", "下巻"),
            overview.volumes.filter { it.isMissingCandidate }.map { it.membership.volumeLabel },
        )
        assertFalse(overview.isConfirmedMainStoryComplete)
        assertTrue(overview.volumes.single { it.membership.volumeLabel == "外伝" }.membership.type == SeriesMembershipType.SIDE_STORY)
        listOf("外伝", "1-2巻").forEach { label ->
            assertFalse(overview.volumes.single { it.membership.volumeLabel == label }.isMissingCandidate)
        }

        assertTrue(repository.removeMembership("member-3"))
        assertEquals(8, database.libraryDao().getAllWorks().size)
        assertTrue(repository.observeCatalog().first().single().volumes.none { it.membership.volumeLabel == "3巻" })

        val restored = repository.confirm(
            SeriesConfirmationTarget.Existing("series"),
            listOf(draft("integer-3", "3巻", SeriesMembershipType.MAIN_STORY)),
        )
        assertEquals(
            SeriesConfirmationResult.Confirmed("series", listOf("member-3-restored")),
            restored,
        )
        assertEquals("3巻", repository.observeCatalog().first().single().volumes.last().membership.volumeLabel)
    }

    @Test
    fun paperbackAndNewEditionRelationCanBeRemovedWithoutChangingEditionOrCopyFacts() = runBlocking {
        seedAnonymousWorks()
        val repository = RoomWorkGroupRepository(
            database,
            idFactory = sequenceOf("group", "member-paperback", "member-new").iterator()::next,
            nowMillis = { 100 },
        )
        val beforeWorks = database.libraryDao().getAllWorks()
        val beforeEditions = database.libraryDao().getAllEditions()
        val beforeCopies = database.libraryDao().getAllCopies()

        assertEquals(
            WorkGroupMutationResult.Linked("group"),
            repository.link(
                "paperback", "new-edition", "匿名年代記 文庫版 1巻", "匿名年代記 新装版 1巻", true,
            ),
        )
        assertTrue(requireNotNull(database.workGroupDao().findGroupById("group")).seriesSubstitutionEnabled)
        assertEquals(2, database.workGroupDao().getAllMemberships().size)

        assertEquals(WorkGroupMutationResult.Unlinked, repository.unlink("member-new"))
        assertTrue(database.workGroupDao().getAllGroups().isEmpty())
        assertEquals(beforeWorks, database.libraryDao().getAllWorks())
        assertEquals(beforeEditions, database.libraryDao().getAllEditions())
        assertEquals(beforeCopies, database.libraryDao().getAllCopies())
    }

    @Test
    fun watchOffDoesNotCallSourceAndOnBaselinesRetriesOutageWithoutDuplicate() = runBlocking {
        database.seriesDao().upsertSeriesItems(
            listOf(dev.ndcshelf.app.data.local.SeriesEntity("series", "匿名年代記", 1, 1)),
        )
        val results = ArrayDeque<SeriesReleaseSourceResult>()
        var calls = 0
        var now = 100L
        val repository = RoomSeriesWatchRepository(
            database = database,
            source = SeriesReleaseSource { _, _ -> calls += 1; results.removeFirst() },
            nowMillis = { now },
            currentYear = { 2026 },
        )

        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.isEmpty())
        assertEquals(0, calls)
        assertEquals(SeriesWatchMutationResult.Updated, repository.setEnabled("series", true))
        results += found("record-1", "匿名年代記 1巻")
        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.isEmpty())

        now = 200
        results += found("record-2", "匿名年代記 2巻")
        val notification = (repository.checkEnabledWatches() as SeriesWatchCheckResult.Success)
            .notifications.single()
        repository.markNotified(notification.candidateIds)
        now = 300
        results += found("record-2", "匿名年代記 2巻")
        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.isEmpty())

        results += SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.OFFLINE)
        val outage = repository.checkEnabledWatches() as SeriesWatchCheckResult.PartialFailure
        assertTrue(outage.retryable)
        assertEquals(2, repository.observeWatches().first().single().candidates.size)
    }

    private suspend fun seedAnonymousWorks() {
        val works = listOf(
            BookWorkEntity("integer-1", "匿名年代記 1巻", "匿名著者"),
            BookWorkEntity("integer-3", "匿名年代記 3巻", "匿名著者"),
            BookWorkEntity("upper", "匿名年代記 上巻", "匿名著者"),
            BookWorkEntity("lower", "匿名年代記 下巻", "匿名著者"),
            BookWorkEntity("side", "匿名年代記 外伝", "匿名著者"),
            BookWorkEntity("omnibus", "匿名年代記 1-2巻 合本", "匿名著者"),
            BookWorkEntity("paperback", "匿名年代記 文庫版 1巻", "匿名著者"),
            BookWorkEntity("new-edition", "匿名年代記 新装版 1巻", "匿名著者"),
        )
        database.libraryDao().upsertWorks(works)
        database.libraryDao().upsertEditions(
            listOf(
                edition("edition-1", "integer-1", "9784820418078", "通常版"),
                edition("edition-paperback", "paperback", "9784000000015", "文庫"),
                edition("edition-new", "new-edition", "9784000000022", "新装版"),
            ),
        )
        database.libraryDao().upsertCopies(
            listOf(
                copy("copy-1", "edition-1"),
                copy("copy-paperback", "edition-paperback"),
                copy("copy-new", "edition-new"),
            ),
        )
    }

    private fun draft(workId: String, label: String, type: SeriesMembershipType) =
        SeriesConfirmationDraft(
            workId = workId,
            volumeLabel = label,
            type = type,
            sourceTitle = when (workId) {
                "integer-1" -> "匿名年代記 1巻"
                "integer-3" -> "匿名年代記 3巻"
                "upper" -> "匿名年代記 上巻"
                "lower" -> "匿名年代記 下巻"
                "side" -> "匿名年代記 外伝"
                else -> "匿名年代記 1-2巻 合本"
            },
            origin = SeriesMembershipOrigin.MANUAL,
        )

    private fun edition(id: String, workId: String, isbn: String, publisher: String) =
        BookEditionEntity(id, workId, isbn, publisher, 2025, null, "913.6", "NDC10", "NDL")

    private fun copy(id: String, editionId: String) =
        OwnedCopyEntity(id, editionId, "PHYSICAL", "テスト棚", "UNREAD", 10)

    private fun found(id: String, title: String) = SeriesReleaseSourceResult.Found(
        listOf(SeriesReleaseSourceCandidate(id, title, "匿名著者", null, "匿名出版社", "2026")),
    )
}
