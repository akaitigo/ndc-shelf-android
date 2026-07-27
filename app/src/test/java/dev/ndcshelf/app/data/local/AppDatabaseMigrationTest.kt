package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(row.locationTierId)
        assertEquals(emptyList<LocationRoomEntity>(), runBlocking { database.locationDao().getRooms() })
        assertEquals("READING", row.readingStatus)
        assertEquals(1_700_000_000_000L, row.addedAt)
        assertEquals("所蔵本", row.copyLabel)
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
    fun version2LocationCopiesReceiveStableOrderKeys() {
        migrationHelper.createDatabase(V2_DATABASE, 2).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '本A', '著者')")
            execSQL(
                "INSERT INTO book_editions VALUES " +
                    "('edition-1', 'work-1', '9784101010014', NULL, NULL, NULL, NULL, NULL, 'UNKNOWN')",
            )
            execSQL("INSERT INTO location_rooms VALUES ('room-1', '書斎', 0)")
            execSQL("INSERT INTO location_shelves VALUES ('shelf-1', 'room-1', '本棚', 0)")
            execSQL("INSERT INTO location_tiers VALUES ('tier-1', 'shelf-1', '上段', 0)")
            execSQL(
                "INSERT INTO owned_copies VALUES " +
                    "('copy-b', 'edition-1', 'PHYSICAL', '旧位置', 'UNREAD', 2, 'tier-1')",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V2_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.query(
            "SELECT location, tierId, shelfOrderKey FROM owned_copies WHERE id = 'copy-b'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧位置", cursor.getString(0))
            assertEquals("tier-1", cursor.getString(1))
            assertEquals("0000000000000002636f70792d62", cursor.getString(2))
        }
        migrated.close()
    }

    @Test
    fun version3CopiesReceiveDefaultLabelWithoutChangingExistingValues() {
        migrationHelper.createDatabase(V3_DATABASE, 3).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '本A', '著者')")
            execSQL(
                "INSERT INTO book_editions VALUES " +
                    "('edition-1', 'work-1', '9784101010014', NULL, NULL, NULL, NULL, NULL, 'UNKNOWN')",
            )
            execSQL(
                "INSERT INTO owned_copies VALUES " +
                    "('copy-1', 'edition-1', 'PHYSICAL', '旧位置', 'READING', 42, NULL, NULL)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V3_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.query(
            "SELECT location, readingStatus, addedAt, copyLabel FROM owned_copies WHERE id = 'copy-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧位置", cursor.getString(0))
            assertEquals("READING", cursor.getString(1))
            assertEquals(42L, cursor.getLong(2))
            assertEquals("所蔵本", cursor.getString(3))
        }
        migrated.close()
    }

    @Test
    fun version4DataIsPreservedAndWishlistTableIsUsable() {
        migrationHelper.createDatabase(V4_DATABASE, 4).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '本A', '著者')")
            execSQL(
                "INSERT INTO book_editions VALUES " +
                    "('edition-1', 'work-1', '9784101010014', NULL, NULL, NULL, NULL, NULL, 'UNKNOWN')",
            )
            execSQL(
                "INSERT INTO owned_copies VALUES " +
                    "('copy-1', 'edition-1', 'PHYSICAL', '棚', 'UNREAD', 42, NULL, NULL, '保存用')",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V4_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.query("SELECT copyLabel FROM owned_copies WHERE id = 'copy-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("保存用", cursor.getString(0))
        }
        migrated.execSQL("INSERT INTO wishlist_items VALUES ('edition-1', 'WANTED', 10, 10)")
        migrated.query("SELECT status FROM wishlist_items WHERE editionId = 'edition-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("WANTED", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun version5DataIsPreservedAndScanSessionTablesAreUsable() {
        migrationHelper.createDatabase(V5_DATABASE, 5).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '本A', '著者')")
            execSQL(
                "INSERT INTO book_editions VALUES " +
                    "('edition-1', 'work-1', '9784101010014', NULL, NULL, NULL, NULL, NULL, 'UNKNOWN')",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V5_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.execSQL("INSERT INTO scan_sessions VALUES ('session-1', 10, NULL)")
        migrated.execSQL(
            "INSERT INTO scan_attempts VALUES " +
                "('attempt-1', 'session-1', '9784101010014', 'DUPLICATE', NULL, NULL, 11, NULL)",
        )
        migrated.query("SELECT outcome FROM scan_attempts WHERE id = 'attempt-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("DUPLICATE", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun version6Migration_preservesRelationsAndAllowsMultipleManualEditionsWithoutIsbn() {
        migrationHelper.createDatabase(V6_DATABASE, 6).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '本A', '著者')")
            execSQL(
                "INSERT INTO book_editions VALUES " +
                    "('edition-1', 'work-1', '9784101010014', NULL, NULL, NULL, NULL, NULL, 'UNKNOWN')",
            )
            execSQL(
                "INSERT INTO owned_copies VALUES " +
                    "('copy-1', 'edition-1', 'PHYSICAL', '棚', 'UNREAD', 42, NULL, NULL, '保存用')",
            )
            execSQL("INSERT INTO wishlist_items VALUES ('edition-1', 'WANTED', 10, 10)")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V6_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.query(
            "SELECT isbn13, bibliographicSource FROM book_editions WHERE id = 'edition-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("9784101010014", cursor.getString(0))
            assertEquals("NDL", cursor.getString(1))
        }
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        migrated.execSQL("INSERT INTO book_works VALUES ('work-2', '手動A', '著者不明')")
        migrated.execSQL("INSERT INTO book_works VALUES ('work-3', '手動B', '著者不明')")
        migrated.execSQL(
            "INSERT INTO book_editions VALUES " +
                "('edition-2', 'work-2', NULL, NULL, NULL, NULL, NULL, NULL, 'UNKNOWN', 'MANUAL')",
        )
        migrated.execSQL(
            "INSERT INTO book_editions VALUES " +
                "('edition-3', 'work-3', NULL, NULL, NULL, NULL, NULL, NULL, 'UNKNOWN', 'MANUAL')",
        )
        migrated.close()
    }

    @Test
    fun version7Migration_preservesWorksAndAddsConstrainedSeriesTables() {
        migrationHelper.createDatabase(V7_DATABASE, 7).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '本編 上', '著者')")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V7_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.query("SELECT title FROM book_works WHERE id = 'work-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("本編 上", cursor.getString(0))
        }
        migrated.query("SELECT COUNT(*) FROM series_memberships").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.assertForeignKey("series_memberships", "series", "seriesId", "id")
        migrated.assertForeignKey("series_memberships", "book_works", "workId", "id")
        migrated.assertIndex("series", "index_series_name_id", unique = false)
        migrated.assertIndex(
            "series_memberships",
            "index_series_memberships_seriesId_workId",
            unique = true,
        )
        migrated.assertIndex(
            "series_memberships",
            "index_series_memberships_seriesId_sortOrderKey",
            unique = true,
        )
        migrated.execSQL("PRAGMA foreign_keys=ON")
        migrated.execSQL("INSERT INTO series VALUES ('series-1', '作品集', 1, 1)")
        migrated.execSQL(
            "INSERT INTO series_memberships " +
                "(id, seriesId, workId, sortOrderKey, volumeLabel, type, createdAt, updatedAt) VALUES " +
                "('membership-1', 'series-1', 'work-1', '80', '上巻', 'MAIN_STORY', 1, 1)",
        )
        migrated.execSQL("DELETE FROM series WHERE id = 'series-1'")
        migrated.query("SELECT COUNT(*) FROM series_memberships").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        migrated.close()
    }

    @Test
    fun version8Migration_preservesMembershipAndAddsConfirmationProvenance() {
        migrationHelper.createDatabase(V8_DATABASE, 8).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '作品 第1巻', '著者')")
            execSQL("INSERT INTO series VALUES ('series-1', '作品', 1, 2)")
            execSQL(
                "INSERT INTO series_memberships VALUES " +
                    "('membership-1', 'series-1', 'work-1', '80', '第1巻', " +
                    "'MAIN_STORY', 1, 2)",
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V8_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.query(
            "SELECT origin, confirmedBy, sourceTitle FROM series_memberships " +
                "WHERE id = 'membership-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("MANUAL", cursor.getString(0))
            assertEquals("USER", cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }
        migrated.execSQL(
            "UPDATE series_memberships SET origin = 'TITLE_SUGGESTION', " +
                "sourceTitle = '作品 第1巻' WHERE id = 'membership-1'",
        )
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        migrated.close()
    }

    @Test
    fun version9MigrationAddsEmptyUsableWorkGroupsWithoutChangingWorks() {
        migrationHelper.createDatabase(V9_DATABASE, 9).apply {
            execSQL("INSERT INTO book_works VALUES ('work-1', '単行本', '著者')")
            execSQL("INSERT INTO book_works VALUES ('work-2', '文庫版', '著者')")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V9_DATABASE,
            APP_DATABASE_VERSION,
            true,
            *AppDatabase.MIGRATIONS.toTypedArray(),
        )
        migrated.query("SELECT id, title FROM book_works ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("単行本", cursor.getString(1))
        }
        migrated.execSQL("PRAGMA foreign_keys=ON")
        migrated.execSQL("INSERT INTO work_groups VALUES ('group-1', '作品', '著者', 1, 1, 1)")
        migrated.execSQL(
            "INSERT INTO work_group_memberships VALUES " +
                "('member-1', 'group-1', 'work-1', 1), ('member-2', 'group-1', 'work-2', 1)",
        )
        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            migrated.execSQL(
                "INSERT INTO work_group_memberships VALUES " +
                    "('member-duplicate', 'group-1', 'work-1', 1)",
            )
        }
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        migrated.close()
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
        const val V2_DATABASE = "migration-v2"
        const val V3_DATABASE = "migration-v3"
        const val V4_DATABASE = "migration-v4"
        const val V5_DATABASE = "migration-v5"
        const val V6_DATABASE = "migration-v6"
        const val V7_DATABASE = "migration-v7"
        const val V8_DATABASE = "migration-v8"
        const val V9_DATABASE = "migration-v9"
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
