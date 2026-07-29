package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.ndcshelf.app.domain.model.ReadingStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 代表データ量でのDBファイル容量予算とMigration完走を検証する
 * （docs/PERFORMANCE_BUDGETS.mdのCI安定指標）。
 *
 * - fixtureはdocs/LIBRARY_SEARCH_PERFORMANCE.mdと同じ分布の匿名生成データだけを使い、
 *   個人蔵書は一切含めない
 * - 所要時間は共有CIで不安定なため断言せず、容量だけを予算と比較する
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseFootprintBudgetTest {
    @get:Rule
    val migrationHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(FOOTPRINT_DATABASE)
    }

    @Test
    fun databaseFileStaysWithinBudgetAt1kAnd5kAnd20kBooks() {
        context.deleteDatabase(FOOTPRINT_DATABASE)
        val database =
            Room
                .databaseBuilder(context, AppDatabase::class.java, FOOTPRINT_DATABASE)
                .allowMainThreadQueries()
                .build()
        try {
            var inserted = 0
            DATABASE_SIZE_BUDGET_BYTES.forEach { (bookCount, budgetBytes) ->
                runBlocking {
                    database.withTransaction {
                        (inserted until bookCount).forEach { index ->
                            insertAnonymousBook(database, index)
                        }
                    }
                }
                inserted = bookCount
                val actualBytes = checkpointedSizeOf(database)
                println("database-footprint,$bookCount,$actualBytes,$budgetBytes")
                assertTrue(
                    "$bookCount books produced a $actualBytes byte database, " +
                        "over the $budgetBytes byte budget in docs/PERFORMANCE_BUDGETS.md.",
                    actualBytes in 1..budgetBytes,
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migrationFromVersion1CompletesWith20kBooksAndPreservesEveryRow() {
        val bookCount = 20_000
        migrationHelper.createDatabase(LEGACY_DATABASE, 1).apply {
            beginTransaction()
            try {
                (0 until bookCount).forEach { index ->
                    execSQL(
                        "INSERT INTO book_works VALUES (?, ?, ?)",
                        arrayOf("work-$index", anonymousTitle(index), "著者 ${index % 431}"),
                    )
                    execSQL(
                        "INSERT INTO book_editions VALUES (?, ?, ?, ?, ?, NULL, ?, 'NDC10', 'NDL')",
                        arrayOf<Any>(
                            "edition-$index",
                            "work-$index",
                            "978${index.toString().padStart(10, '0')}",
                            "出版社",
                            2024,
                            "%03d".format(index % 1_000),
                        ),
                    )
                    execSQL(
                        "INSERT INTO owned_copies VALUES (?, ?, 'PHYSICAL', ?, ?, ?)",
                        arrayOf<Any>(
                            "copy-$index",
                            "edition-$index",
                            "部屋${index % 8} / 棚${index % 32} / 段${index % 5}",
                            ReadingStatus.entries[index % ReadingStatus.entries.size].name,
                            1_700_000_000_000L + index,
                        ),
                    )
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
            close()
        }

        val migrated =
            migrationHelper.runMigrationsAndValidate(
                LEGACY_DATABASE,
                APP_DATABASE_VERSION,
                true,
                *AppDatabase.MIGRATIONS.toTypedArray(),
            )
        listOf("book_works", "book_editions", "owned_copies").forEach { table ->
            migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("$table lost rows during migration", bookCount, cursor.getInt(0))
            }
        }
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        migrated
            .query("SELECT readingStatus FROM owned_copies WHERE id = 'copy-19999'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    ReadingStatus.entries[19_999 % ReadingStatus.entries.size].name,
                    cursor.getString(0),
                )
            }
        migrated.close()
    }

    private suspend fun insertAnonymousBook(
        database: AppDatabase,
        index: Int,
    ) {
        val dao = database.libraryDao()
        dao.insertWork(BookWorkEntity("work-$index", anonymousTitle(index), "著者 ${index % 431}"))
        dao.insertEdition(
            BookEditionEntity(
                id = "edition-$index",
                workId = "work-$index",
                isbn13 = "978${index.toString().padStart(10, '0')}",
                publisher = "出版社",
                publishedYear = 2024,
                coverUrl = null,
                ndcCode = "%03d".format(index % 1_000),
                ndcEdition = "NDC10",
                classificationSource = "NDL",
            ),
        )
        dao.insertCopy(
            OwnedCopyEntity(
                id = "copy-$index",
                editionId = "edition-$index",
                mediaType = "PHYSICAL",
                location = "部屋${index % 8} / 棚${index % 32} / 段${index % 5}",
                readingStatus = ReadingStatus.entries[index % ReadingStatus.entries.size].name,
                addedAt = 1_700_000_000_000L + index,
                shelfOrderKey = "%08d".format(index),
                copyLabel = "所蔵本",
            ),
        )
    }

    private fun anonymousTitle(index: Int): String = if (index % 97 == 0) "郷土資料 $index" else "資料 $index"

    /** WALをメインDBへ反映してから、DB本体とWAL/SHMの合計サイズを返す。 */
    private fun checkpointedSizeOf(database: AppDatabase): Long {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { it.moveToFirst() }
        val databaseFile = context.getDatabasePath(FOOTPRINT_DATABASE)
        return listOf("", "-wal", "-shm")
            .map { suffix -> File(databaseFile.path + suffix) }
            .filter(File::exists)
            .sumOf(File::length)
    }

    private companion object {
        const val FOOTPRINT_DATABASE = "footprint-budget.db"
        const val LEGACY_DATABASE = "footprint-migration-v1.db"

        /**
         * 実測値（2026-07-30、Robolectric/SQLite: 1,000冊=778,240 / 5,000冊=2,232,320 /
         * 20,000冊=7,823,360バイト）+約50%の予算。
         * 超過時はdocs/PERFORMANCE_BUDGETS.mdの根拠を更新してから引き上げる。
         */
        val DATABASE_SIZE_BUDGET_BYTES =
            listOf(
                1_000 to 1_200_000L,
                5_000 to 3_500_000L,
                20_000 to 12_000_000L,
            )
    }
}
