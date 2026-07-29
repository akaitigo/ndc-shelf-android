package dev.ndcshelf.app.background

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.consent.RoomConsentRepository
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.remote.SeriesReleaseSource
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceCandidate
import dev.ndcshelf.app.data.remote.SeriesReleaseSourceResult
import dev.ndcshelf.app.data.repository.RoomSeriesWatchRepository
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同意なし・撤回後にネットワーク境界（SeriesReleaseSource）へ一切
 * 到達しないことを、実Roomと実runnerで検証する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeriesWatchConsentGateIntegrationTest {
    private lateinit var database: AppDatabase
    private var sourceCalls = 0

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        runBlocking {
            database.seriesDao().upsertSeries(SeriesEntity("series", "年代記", 1, 1))
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun withoutConsentRunnerNeverTouchesNetworkBoundary() =
        runBlocking {
            val consents = RoomConsentRepository(database)
            val runner = SeriesWatchRunner(watchRepository(), notifier(), consents)

            val result = runner.run()

            assertEquals(SeriesWatchRunResult.SUCCESS, result)
            assertEquals(0, sourceCalls)
        }

    @Test
    fun afterRevocationRunnerNeverTouchesNetworkBoundary() =
        runBlocking {
            val consents = RoomConsentRepository(database)
            consents.grant(ConsentPurpose.SERIES_RELEASE_WATCH)
            consents.revoke(ConsentPurpose.SERIES_RELEASE_WATCH)
            val runner = SeriesWatchRunner(watchRepository(), notifier(), consents)

            val result = runner.run()

            assertEquals(SeriesWatchRunResult.SUCCESS, result)
            assertEquals(0, sourceCalls)
        }

    @Test
    fun withConsentRunnerReachesNetworkBoundary() =
        runBlocking {
            val consents = RoomConsentRepository(database)
            consents.grant(ConsentPurpose.SERIES_RELEASE_WATCH)
            val watches = watchRepository()
            watches.setEnabled("series", true)
            val runner = SeriesWatchRunner(watches, notifier(), consents)

            runner.run()

            assertEquals(1, sourceCalls)
        }

    private fun watchRepository() =
        RoomSeriesWatchRepository(
            database = database,
            source =
                SeriesReleaseSource { _, _ ->
                    sourceCalls += 1
                    SeriesReleaseSourceResult.Found(
                        listOf(
                            SeriesReleaseSourceCandidate(
                                sourceRecordId = "record-1",
                                title = "年代記 1",
                                primaryAuthor = "著者",
                                isbn13 = null,
                                publisher = "出版社",
                                publishedDate = "2026",
                            ),
                        ),
                    )
                },
            nowMillis = { 100L },
            currentYear = { 2026 },
        )

    private fun notifier() = SeriesReleaseNotifier { emptySet() }
}
