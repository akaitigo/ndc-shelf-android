package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.ReadingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主要クエリがインデックスを使い、意図しないfull table scanへ退行しないことを
 * EXPLAIN QUERY PLANで断言する（docs/PERFORMANCE_BUDGETS.mdのCI安定指標）。
 *
 * SQLiteのプラン文字列仕様:
 * - `SCAN <table>`（USING INDEXなし）= full table scan
 * - `SCAN <table> USING [COVERING] INDEX` = インデックス順の走査（索引は使っている）
 * - `SEARCH <table> USING ...` = インデックスまたは主キーによる絞り込み
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueryPlanIndexUsageTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun librarySearchByTextScansOnlyTheDrivingTableAndJoinsThroughIndexes() {
        val plan = planOf(LibrarySearchCriteria(query = "郷土").toSQLiteQuery())

        // LIKE部分一致はB-tree索引を使えないため、駆動表1つのfull scanだけを許容する。
        assertEquals(plan.describe(), 1, plan.fullScans().size)
        assertTrue(plan.describe(), plan.joins().all { it.startsWith("SEARCH") })
    }

    @Test
    fun librarySearchByReadingStatusJoinsThroughIndexes() {
        val plan =
            planOf(
                LibrarySearchCriteria(readingStatus = ReadingStatus.READING).toSQLiteQuery(),
            )

        assertTrue(plan.describe(), plan.fullScans().size <= 1)
        assertTrue(plan.describe(), plan.joins().all { it.startsWith("SEARCH") })
    }

    @Test
    fun librarySearchBySelectedEditionUsesTheEditionIndexForNeighborLookup() {
        val plan =
            planOf(LibrarySearchCriteria(selectedEditionId = "edition-1").toSQLiteQuery())

        assertTrue(plan.describe(), plan.any { "index_owned_copies_editionId" in it })
    }

    @Test
    fun ownedBookLookupsByIsbnAndCopyIdNeverFullScan() {
        val byIsbn =
            planOf(
                LIBRARY_ROW_SELECT + "WHERE editions.isbn13 = ? LIMIT 1",
                "9784820418078",
            )
        val byCopyId =
            planOf(
                LIBRARY_ROW_SELECT + "WHERE copies.id = ? LIMIT 1",
                "copy-1",
            )

        assertEquals(byIsbn.describe(), emptyList<String>(), byIsbn.fullScans())
        assertTrue(byIsbn.describe(), byIsbn.any { "index_book_editions_isbn13" in it })
        assertEquals(byCopyId.describe(), emptyList<String>(), byCopyId.fullScans())
    }

    @Test
    fun seriesMembershipQueriesUseTheSeriesIndexes() {
        val membershipsForSeries =
            planOf(
                "SELECT * FROM series_memberships WHERE seriesId = ? ORDER BY sortOrderKey, id",
                "series-1",
            )
        val unassignedWorks =
            planOf(
                """
                SELECT works.* FROM book_works AS works
                WHERE NOT EXISTS (
                    SELECT 1 FROM series_memberships AS memberships
                    WHERE memberships.workId = works.id
                )
                ORDER BY works.title, works.id
                """.trimIndent(),
            )

        assertEquals(
            membershipsForSeries.describe(),
            emptyList<String>(),
            membershipsForSeries.fullScans(),
        )
        assertTrue(
            membershipsForSeries.describe(),
            membershipsForSeries.any { "index_series_memberships_seriesId" in it },
        )
        // 相関サブクエリ側はworkId索引で照合する（駆動表book_worksの走査は仕様）。
        assertTrue(
            unassignedWorks.describe(),
            unassignedWorks.any { "index_series_memberships_workId" in it },
        )
    }

    @Test
    fun syncQueueQueriesUseTheStateAndCounterIndexes() {
        val pendingOperations =
            planOf(
                "SELECT * FROM sync_operations WHERE state = 'LOCAL_PENDING' " +
                    "ORDER BY deviceId, counter LIMIT ?",
                100,
            )
        val countersAfter =
            planOf(
                "SELECT counter FROM sync_operations WHERE deviceId = ? AND counter > ? " +
                    "ORDER BY counter LIMIT 1001",
                "device-1",
                0,
            )
        val pendingCount =
            planOf("SELECT COUNT(*) FROM sync_operations WHERE state = 'LOCAL_PENDING'")

        assertEquals(
            pendingOperations.describe(),
            emptyList<String>(),
            pendingOperations.fullScans(),
        )
        assertTrue(
            pendingOperations.describe(),
            pendingOperations.any { "index_sync_operations_state_deviceId_counter" in it },
        )
        assertEquals(countersAfter.describe(), emptyList<String>(), countersAfter.fullScans())
        assertTrue(
            countersAfter.describe(),
            countersAfter.any { "index_sync_operations_deviceId_counter" in it },
        )
        assertTrue(
            pendingCount.describe(),
            pendingCount.any { "index_sync_operations_state_deviceId_counter" in it },
        )
    }

    @Test
    fun seriesWatchQueriesUseTheEnabledAndSeriesIndexes() {
        val enabledWatches =
            planOf("SELECT * FROM series_watches WHERE enabled = 1 ORDER BY seriesId")
        val candidatesForSeries =
            planOf(
                "SELECT * FROM series_release_candidates WHERE seriesId = ? ORDER BY id",
                "series-1",
            )

        assertEquals(enabledWatches.describe(), emptyList<String>(), enabledWatches.fullScans())
        assertTrue(
            enabledWatches.describe(),
            enabledWatches.any { "index_series_watches_enabled" in it },
        )
        assertEquals(
            candidatesForSeries.describe(),
            emptyList<String>(),
            candidatesForSeries.fullScans(),
        )
        assertTrue(
            candidatesForSeries.describe(),
            candidatesForSeries.any { "index_series_release_candidates_seriesId" in it },
        )
    }

    private fun planOf(query: SupportSQLiteQuery): List<String> =
        readPlan(SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.sql}", query.boundArguments()))

    private fun planOf(
        sql: String,
        vararg arguments: Any?,
    ): List<String> = readPlan(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", arrayOf(*arguments)))

    private fun readPlan(query: SupportSQLiteQuery): List<String> =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            val detailColumn = cursor.getColumnIndexOrThrow("detail")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(detailColumn))
            }
        }

    /** `SCAN table`（索引なしのfull table scan）の行だけを抜き出す。 */
    private fun List<String>.fullScans(): List<String> = filter { it.startsWith("SCAN") && "USING" !in it }

    /** 駆動表以外の結合行（2行目以降のSCAN/SEARCH）を返す。 */
    private fun List<String>.joins(): List<String> = filter { it.startsWith("SCAN") || it.startsWith("SEARCH") }.drop(1)

    private fun List<String>.describe(): String = joinToString(prefix = "plan=[", postfix = "]")

    private companion object {
        val LIBRARY_ROW_SELECT =
            """
            SELECT copies.id AS copyId
            FROM owned_copies AS copies
            INNER JOIN book_editions AS editions ON editions.id = copies.editionId
            INNER JOIN book_works AS works ON works.id = editions.workId
            LEFT JOIN location_tiers AS tiers ON tiers.id = copies.tierId
            LEFT JOIN location_shelves AS shelves ON shelves.id = tiers.shelfId
            LEFT JOIN location_rooms AS rooms ON rooms.id = shelves.roomId
            """.trimIndent() + "\n"
    }
}

/** SupportSQLiteQueryのバインド引数を配列として取り出す。 */
private fun SupportSQLiteQuery.boundArguments(): Array<Any?> {
    val values = arrayOfNulls<Any?>(argCount)
    bindTo(
        object : androidx.sqlite.db.SupportSQLiteProgram {
            override fun bindBlob(
                index: Int,
                value: ByteArray,
            ) {
                values[index - 1] = value
            }

            override fun bindDouble(
                index: Int,
                value: Double,
            ) {
                values[index - 1] = value
            }

            override fun bindLong(
                index: Int,
                value: Long,
            ) {
                values[index - 1] = value
            }

            override fun bindNull(index: Int) {
                values[index - 1] = null
            }

            override fun bindString(
                index: Int,
                value: String,
            ) {
                values[index - 1] = value
            }

            override fun clearBindings() {
                values.fill(null)
            }

            override fun close() = Unit
        },
    )
    return values
}
