package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun version1Fixture_definesConstraintsAndPreservesValuesWhenOpenedByRoom() {
        migrationHelper.createDatabase(V1_DATABASE, 1).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '題名', '著者')")
            execSQL(
                """
                INSERT INTO book_editions VALUES (
                    'edition-1', 'work-1', '9784820418078', '出版社', 2024,
                    'https://ndlsearch.ndl.go.jp/thumbnail/9784820418078.jpg',
                    '014.45', 'NDC10', 'NDL'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO owned_copies VALUES (
                    'copy-1', 'edition-1', 'PHYSICAL', '書斎・棚A', 'READING', 1700000000000
                )
                """.trimIndent(),
            )

            assertEquals(
                EXPECTED_WORK_COLUMNS,
                queryNames("PRAGMA table_info(book_works)", "name"),
            )
            assertEquals(
                EXPECTED_EDITION_COLUMNS,
                queryNames("PRAGMA table_info(book_editions)", "name"),
            )
            assertEquals(
                EXPECTED_COPY_COLUMNS,
                queryNames("PRAGMA table_info(owned_copies)", "name"),
            )
            assertIndex("book_editions", "index_book_editions_workId", unique = false)
            assertIndex("book_editions", "index_book_editions_isbn13", unique = true)
            assertIndex("owned_copies", "index_owned_copies_editionId", unique = false)
            assertForeignKey(
                table = "book_editions",
                parent = "book_works",
                from = "workId",
                to = "id",
            )
            assertForeignKey(
                table = "owned_copies",
                parent = "book_editions",
                from = "editionId",
                to = "id",
            )
            close()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, V1_DATABASE)
            .addMigrations(*AppDatabase.MIGRATIONS.toTypedArray())
            .allowMainThreadQueries()
            .build()
        migrationHelper.closeWhenFinished(database)

        val row = runBlocking {
            requireNotNull(database.libraryDao().findOwnedByCopyId("copy-1"))
        }
        assertEquals("題名", row.title)
        assertEquals("出版社", row.publisher)
        assertEquals(2024, row.publishedYear)
        assertEquals("書斎・棚A", row.location)
        assertEquals("READING", row.readingStatus)
        assertEquals(1_700_000_000_000L, row.addedAt)
    }

    @Test
    fun everyExportedSchemaHasARegisteredPathToCurrentVersion() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val versions = requireNotNull(assets.list(SCHEMA_ASSET_FOLDER))
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .sorted()

        assertEquals((1..APP_DATABASE_VERSION).toList(), versions)
        assertEquals(
            (1 until APP_DATABASE_VERSION).toList(),
            AppDatabase.MIGRATIONS.map { it.startVersion }.sorted(),
        )
        AppDatabase.MIGRATIONS.forEach { migration ->
            assertEquals(migration.startVersion + 1, migration.endVersion)
        }

        versions.filter { it < APP_DATABASE_VERSION }.forEach { startVersion ->
            val name = "migration-$startVersion-to-$APP_DATABASE_VERSION"
            migrationHelper.createDatabase(name, startVersion).close()
            migrationHelper.runMigrationsAndValidate(
                name,
                APP_DATABASE_VERSION,
                true,
                *AppDatabase.MIGRATIONS.toTypedArray(),
            ).close()
        }
    }

    @Test
    fun malformedExistingSchema_isRejectedInsteadOfBeingDestructivelyRecreated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(CORRUPT_DATABASE)
        context.openOrCreateDatabase(CORRUPT_DATABASE, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL("CREATE TABLE book_works (id TEXT PRIMARY KEY NOT NULL)")
            sqlite.version = APP_DATABASE_VERSION
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, CORRUPT_DATABASE)
            .addMigrations(*AppDatabase.MIGRATIONS.toTypedArray())
            .allowMainThreadQueries()
            .build()

        try {
            assertThrows(IllegalStateException::class.java) {
                database.openHelper.writableDatabase
            }
        } finally {
            database.close()
            context.deleteDatabase(CORRUPT_DATABASE)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.queryNames(
        sql: String,
        column: String,
    ): Set<String> = query(sql).use { cursor ->
        buildSet {
            val index = cursor.getColumnIndexOrThrow(column)
            while (cursor.moveToNext()) add(cursor.getString(index))
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertForeignKey(
        table: String,
        parent: String,
        from: String,
        to: String,
    ) {
        query("PRAGMA foreign_key_list($table)").use { cursor ->
            var matched = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("table")) == parent &&
                    cursor.getString(cursor.getColumnIndexOrThrow("from")) == from &&
                    cursor.getString(cursor.getColumnIndexOrThrow("to")) == to &&
                    cursor.getString(cursor.getColumnIndexOrThrow("on_delete")) == "CASCADE"
                ) {
                    matched = true
                }
            }
            assertTrue("Missing $table($from) -> $parent($to) CASCADE", matched)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertIndex(
        table: String,
        name: String,
        unique: Boolean,
    ) {
        query("PRAGMA index_list($table)").use { cursor ->
            var matched = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == name &&
                    (cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1) == unique
                ) {
                    matched = true
                }
            }
            assertTrue("Missing index $name (unique=$unique)", matched)
        }
    }

    private companion object {
        const val V1_DATABASE = "migration-v1"
        const val CORRUPT_DATABASE = "migration-corrupt"
        const val SCHEMA_ASSET_FOLDER = "dev.ndcshelf.app.data.local.AppDatabase"

        val EXPECTED_WORK_COLUMNS = setOf("id", "title", "primaryAuthor")
        val EXPECTED_EDITION_COLUMNS = setOf(
            "id",
            "workId",
            "isbn13",
            "publisher",
            "publishedYear",
            "coverUrl",
            "ndcCode",
            "ndcEdition",
            "classificationSource",
        )
        val EXPECTED_COPY_COLUMNS = setOf(
            "id",
            "editionId",
            "mediaType",
            "location",
            "readingStatus",
            "addedAt",
        )
    }
}
