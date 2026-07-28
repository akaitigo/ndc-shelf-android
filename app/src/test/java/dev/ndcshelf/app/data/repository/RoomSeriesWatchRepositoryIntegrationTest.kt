package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.WishlistItemEntity
import dev.ndcshelf.app.data.remote.SeriesReleaseSource
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceCandidate
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceFailure
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceResult
import dev.ndcshelf.app.domain.repository.SeriesWatchCheckResult
import dev.ndcshelf.app.domain.repository.SeriesWatchMutationResult
import dev.ndcshelf.app.domain.model.SeriesReleaseState
import kotlinx.coroutines.flow.first
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
class RoomSeriesWatchRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private val results = ArrayDeque<SeriesReleaseSourceResult>()
    private var now = 100L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            database.seriesDao().upsertSeries(SeriesEntity("series", "年代記", 1, 1))
        }
    }

    @After fun tearDown() = database.close()

    @Test
    fun optInBaselinesThenOnlyNotifiesFirstSeenCandidateOnce() = runBlocking {
        val repository = repository()
        assertEquals(SeriesWatchMutationResult.Updated, repository.setEnabled("series", true))
        results += found("record-1", "年代記 1")
        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.isEmpty())

        now += CHECK_INTERVAL
        results += SeriesReleaseSourceResult.Found(
            listOf(candidate("record-1", "年代記 1"), candidate("record-2", "年代記 2")),
        )
        val next = repository.checkEnabledWatches() as SeriesWatchCheckResult.Success
        assertEquals(listOf("年代記 2"), next.notifications.single().candidateTitles)
        repository.markNotified(next.notifications.single().candidateIds)

        now += CHECK_INTERVAL
        results += found("record-2", "年代記 2")
        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.isEmpty())
        assertEquals(2, repository.observeWatches().first().single().candidates.size)
    }

    @Test
    fun outageKeepsLocalCandidatesAndClockRollbackCannotRegressTimestamps() = runBlocking {
        val repository = repository()
        repository.setEnabled("series", true)
        results += found("record-1", "年代記 1")
        repository.checkEnabledWatches()

        now += CHECK_INTERVAL
        results += SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.OFFLINE)
        val failure = repository.checkEnabledWatches() as SeriesWatchCheckResult.PartialFailure

        assertTrue(failure.retryable)
        var overview = repository.observeWatches().first().single()
        assertEquals(1, overview.candidates.size)
        assertEquals(CHECK_INTERVAL + 100, overview.watch.lastCheckedAt)
        assertEquals(100L, overview.watch.lastSuccessfulAt)

        now = 50
        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.isEmpty())
        overview = repository.observeWatches().first().single()
        assertEquals(CHECK_INTERVAL + 100, overview.watch.lastCheckedAt)
        assertEquals(100L, overview.watch.lastSuccessfulAt)
    }

    @Test
    fun disabledOrUnknownSeriesDoesNotCallSource() = runBlocking {
        val repository = repository()
        assertEquals(SeriesWatchMutationResult.Invalid, repository.setEnabled("missing", true))
        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.isEmpty())
        assertTrue(results.isEmpty())
        assertNull(database.seriesWatchDao().findWatch("missing"))
    }

    @Test
    fun undeliveredCandidateRetriesAndIsbnReflectsReservedThenOwnedState() = runBlocking {
        val repository = repository()
        repository.setEnabled("series", true)
        results += found("record-1", "年代記 1")
        repository.checkEnabledWatches()
        now += CHECK_INTERVAL
        val release = candidate("record-2", "年代記 2").copy(isbn13 = ISBN)
        results += SeriesReleaseSourceResult.Found(listOf(release))
        assertEquals(1, (repository.checkEnabledWatches() as SeriesWatchCheckResult.Success).notifications.size)

        now += CHECK_INTERVAL
        results += SeriesReleaseSourceResult.Found(listOf(release))
        val retried = repository.checkEnabledWatches() as SeriesWatchCheckResult.Success
        assertEquals(1, retried.notifications.size)
        now = 50
        repository.markNotified(retried.notifications.single().candidateIds)
        assertEquals(
            CHECK_INTERVAL + 100,
            database.seriesWatchDao().findCandidate(retried.notifications.single().candidateIds.single())
                ?.notifiedAt,
        )

        val library = database.libraryDao()
        library.upsertWorks(listOf(BookWorkEntity("work", "年代記 2", "著者")))
        library.upsertEditions(
            listOf(BookEditionEntity("edition", "work", ISBN, null, 2026, null, null, null, "UNKNOWN")),
        )
        library.upsertWishlistItems(listOf(WishlistItemEntity("edition", "RESERVED", 1, 2)))
        assertEquals(
            SeriesReleaseState.RESERVED,
            repository.observeWatches().first().single().candidates.last().state,
        )

        library.upsertCopies(
            listOf(OwnedCopyEntity("copy", "edition", "PHYSICAL", "未設定", "UNREAD", 4)),
        )
        assertEquals(
            SeriesReleaseState.OWNED,
            repository.observeWatches().first().single().candidates.last().state,
        )
    }

    @Test
    fun retrySkipsSeriesSuccessfullyCheckedWithinSevenDays() = runBlocking {
        database.seriesDao().upsertSeries(SeriesEntity("series-2", "年代記 続編", 1, 1))
        val queries = mutableListOf<String>()
        val repository = RoomSeriesWatchRepository(
            database = database,
            source = SeriesReleaseSource { title, _ ->
                queries += title
                if (title == "年代記") found("record-1", "年代記 1")
                else SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.OFFLINE)
            },
            nowMillis = { now },
            currentYear = { 2026 },
        )
        repository.setEnabled("series", true)
        repository.setEnabled("series-2", true)

        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.PartialFailure).retryable)
        now += 60 * 60 * 1_000
        assertTrue((repository.checkEnabledWatches() as SeriesWatchCheckResult.PartialFailure).retryable)

        assertEquals(listOf("年代記", "年代記 続編", "年代記 続編"), queries)
    }

    private fun repository() = RoomSeriesWatchRepository(
        database = database,
        source = SeriesReleaseSource { _, _ -> results.removeFirst() },
        nowMillis = { now },
        currentYear = { 2026 },
    )

    private fun found(id: String, title: String) = SeriesReleaseSourceResult.Found(listOf(candidate(id, title)))
    private fun candidate(id: String, title: String) = SeriesReleaseSourceCandidate(
        sourceRecordId = id,
        title = title,
        primaryAuthor = "著者",
        isbn13 = null,
        publisher = "出版社",
        publishedDate = "2026",
    )

    private companion object {
        const val ISBN = "9784820418078"
        const val CHECK_INTERVAL = 7L * 24 * 60 * 60 * 1_000
    }
}
