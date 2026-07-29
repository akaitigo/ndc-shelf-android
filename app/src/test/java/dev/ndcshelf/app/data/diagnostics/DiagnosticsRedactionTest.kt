package dev.ndcshelf.app.data.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.consent.RoomConsentRepository
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsSection
import dev.ndcshelf.app.domain.diagnostics.NoOpDiagnosticsLogger
import dev.ndcshelf.app.domain.diagnostics.buildDiagnosticsReport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 実データを模した個人値を投入した状態で全セクションの診断ファイルを生成し、
 * 個人値が一切含まれないことを走査する（実データ混入スキャン）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsRedactionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: AppDatabase

    private val personalValues =
        listOf(
            "医療と心の秘密の本",
            "秘匿著者名",
            "9784999999991",
            "寝室・棚C",
            "うつ病",
            "https://example.com/secret",
        )

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
            database.libraryDao().insertWork(
                BookWorkEntity("work-1", "医療と心の秘密の本", "秘匿著者名"),
            )
            database.libraryDao().insertEdition(
                BookEditionEntity(
                    id = "edition-1",
                    workId = "work-1",
                    isbn13 = "9784999999991",
                    publisher = "寝室・棚C",
                    publishedYear = 2026,
                    coverUrl = "https://example.com/secret",
                    ndcCode = "493.7",
                    ndcEdition = "NDC10",
                    classificationSource = "MANUAL",
                ),
            )
            database.seriesDao().upsertSeries(SeriesEntity("series-1", "うつ病", 1, 1))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fullReportNeverContainsPersonalValuesFromTheLibrary() =
        runBlocking {
            val logger = FileDiagnosticsLogger(folder.root)
            logger.log(DiagnosticCode.NDL_TIMEOUT)
            val consents = RoomConsentRepository(database)
            consents.grant(ConsentPurpose.SERIES_RELEASE_WATCH)
            val collector =
                RoomDiagnosticsSnapshotCollector(
                    database = database,
                    consentRepository = consents,
                    logger = logger,
                    appVersionName = "0.4.0",
                    appVersionCode = 6,
                    androidSdkInt = 35,
                )

            val report =
                buildDiagnosticsReport(
                    collector.collect(),
                    DiagnosticsSection.entries.toSet(),
                ).toString()

            personalValues.forEach { value ->
                assertFalse("診断ファイルへ個人値が混入: $value", report.contains(value))
            }
            assertTrue(report.contains("\"works\":1"))
            assertTrue(report.contains("NDL_TIMEOUT"))
            assertTrue(report.contains("SERIES_RELEASE_WATCH"))
        }

    @Test
    fun unselectedSectionsAreExcludedFromTheReport() =
        runBlocking {
            val collector =
                RoomDiagnosticsSnapshotCollector(
                    database = database,
                    consentRepository = RoomConsentRepository(database),
                    logger = NoOpDiagnosticsLogger,
                    appVersionName = "0.4.0",
                    appVersionCode = 6,
                    androidSdkInt = 35,
                )

            val report =
                buildDiagnosticsReport(
                    collector.collect(),
                    setOf(DiagnosticsSection.APP_AND_DEVICE),
                ).toString()

            assertTrue(report.contains("appAndDevice"))
            assertFalse(report.contains("libraryCounts"))
            assertFalse(report.contains("syncState"))
            assertFalse(report.contains("consentedPurposes"))
            assertFalse(report.contains("recentEvents"))
        }

    @Test
    fun snapshotCountsMatchTheSeededLibrary() =
        runBlocking {
            val collector =
                RoomDiagnosticsSnapshotCollector(
                    database = database,
                    consentRepository = RoomConsentRepository(database),
                    logger = NoOpDiagnosticsLogger,
                    appVersionName = "0.4.0",
                    appVersionCode = 6,
                    androidSdkInt = 35,
                )

            val snapshot = collector.collect()

            assertEquals(1, snapshot.workCount)
            assertEquals(1, snapshot.editionCount)
            assertEquals(0, snapshot.copyCount)
            assertEquals(1, snapshot.seriesCount)
            assertFalse(snapshot.syncEnabled)
        }
}
