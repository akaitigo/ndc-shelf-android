package dev.ndcshelf.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val APP_DATABASE_VERSION = 10

@Database(
    entities = [
        BookWorkEntity::class,
        WorkGroupEntity::class,
        WorkGroupMembershipEntity::class,
        SeriesEntity::class,
        SeriesMembershipEntity::class,
        BookEditionEntity::class,
        LocationRoomEntity::class,
        LocationShelfEntity::class,
        LocationTierEntity::class,
        OwnedCopyEntity::class,
        WishlistItemEntity::class,
        ScanSessionEntity::class,
        ScanAttemptEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "ndc-shelf.db"

        // Register every manual migration here so production and migration tests use one graph.
        val MIGRATIONS: List<Migration> = listOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )
    }

    abstract fun libraryDao(): LibraryDao

    abstract fun locationDao(): LocationDao

    abstract fun seriesDao(): SeriesDao

    abstract fun workGroupDao(): WorkGroupDao
}

private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS work_groups (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                primaryAuthor TEXT NOT NULL,
                seriesSubstitutionEnabled INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_work_groups_title_id ON work_groups(title, id)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS work_group_memberships (
                id TEXT NOT NULL PRIMARY KEY,
                groupId TEXT NOT NULL,
                workId TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(groupId) REFERENCES work_groups(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(workId) REFERENCES book_works(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_work_group_memberships_groupId " +
                "ON work_group_memberships(groupId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_work_group_memberships_workId " +
                "ON work_group_memberships(workId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_work_group_memberships_groupId_workId " +
                "ON work_group_memberships(groupId, workId)",
        )
    }
}

private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE series_memberships " +
                "ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'",
        )
        db.execSQL(
            "ALTER TABLE series_memberships " +
                "ADD COLUMN confirmedBy TEXT NOT NULL DEFAULT 'USER'",
        )
        db.execSQL(
            "ALTER TABLE series_memberships " +
                "ADD COLUMN sourceTitle TEXT NOT NULL DEFAULT ''",
        )
    }
}

private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS series (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_series_name_id ON series(name, id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS series_memberships (
                id TEXT NOT NULL PRIMARY KEY,
                seriesId TEXT NOT NULL,
                workId TEXT NOT NULL,
                sortOrderKey TEXT NOT NULL,
                volumeLabel TEXT NOT NULL,
                type TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(seriesId) REFERENCES series(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(workId) REFERENCES book_works(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_series_memberships_seriesId " +
                "ON series_memberships(seriesId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_series_memberships_workId " +
                "ON series_memberships(workId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_series_memberships_seriesId_workId " +
                "ON series_memberships(seriesId, workId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_series_memberships_seriesId_sortOrderKey " +
                "ON series_memberships(seriesId, sortOrderKey)",
        )
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE book_editions RENAME TO book_editions_old")
        db.execSQL(
            """
            CREATE TABLE book_editions (
                id TEXT NOT NULL PRIMARY KEY,
                workId TEXT NOT NULL,
                isbn13 TEXT,
                publisher TEXT,
                publishedYear INTEGER,
                coverUrl TEXT,
                ndcCode TEXT,
                ndcEdition TEXT,
                classificationSource TEXT NOT NULL,
                bibliographicSource TEXT NOT NULL,
                FOREIGN KEY(workId) REFERENCES book_works(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO book_editions (
                id, workId, isbn13, publisher, publishedYear, coverUrl,
                ndcCode, ndcEdition, classificationSource, bibliographicSource
            )
            SELECT id, workId, isbn13, publisher, publishedYear, coverUrl,
                ndcCode, ndcEdition, classificationSource, 'NDL'
            FROM book_editions_old
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE owned_copies_new (
                id TEXT NOT NULL PRIMARY KEY,
                editionId TEXT NOT NULL,
                mediaType TEXT NOT NULL,
                location TEXT NOT NULL,
                readingStatus TEXT NOT NULL,
                addedAt INTEGER NOT NULL,
                tierId TEXT,
                shelfOrderKey TEXT,
                copyLabel TEXT NOT NULL,
                FOREIGN KEY(editionId) REFERENCES book_editions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(tierId) REFERENCES location_tiers(id)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO owned_copies_new (
                id, editionId, mediaType, location, readingStatus, addedAt,
                tierId, shelfOrderKey, copyLabel
            )
            SELECT id, editionId, mediaType, location, readingStatus, addedAt,
                tierId, shelfOrderKey, copyLabel
            FROM owned_copies
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE wishlist_items_new (
                editionId TEXT NOT NULL PRIMARY KEY,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(editionId) REFERENCES book_editions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO wishlist_items_new (editionId, status, createdAt, updatedAt)
            SELECT editionId, status, createdAt, updatedAt FROM wishlist_items
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE owned_copies")
        db.execSQL("DROP TABLE wishlist_items")
        db.execSQL("DROP TABLE book_editions_old")
        db.execSQL("ALTER TABLE owned_copies_new RENAME TO owned_copies")
        db.execSQL("ALTER TABLE wishlist_items_new RENAME TO wishlist_items")
        db.execSQL("CREATE INDEX index_book_editions_workId ON book_editions(workId)")
        db.execSQL("CREATE UNIQUE INDEX index_book_editions_isbn13 ON book_editions(isbn13)")
        db.execSQL("CREATE INDEX index_owned_copies_editionId ON owned_copies(editionId)")
        db.execSQL("CREATE INDEX index_owned_copies_tierId ON owned_copies(tierId)")
        db.execSQL(
            "CREATE INDEX index_owned_copies_tierId_shelfOrderKey " +
                "ON owned_copies(tierId, shelfOrderKey)",
        )
        db.execSQL("CREATE INDEX index_wishlist_items_status ON wishlist_items(status)")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_sessions (
                id TEXT NOT NULL PRIMARY KEY,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_sessions_endedAt ON scan_sessions(endedAt)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_attempts (
                id TEXT NOT NULL PRIMARY KEY,
                sessionId TEXT NOT NULL,
                isbn TEXT NOT NULL,
                outcome TEXT NOT NULL,
                copyId TEXT,
                copySnapshot TEXT,
                attemptedAt INTEGER NOT NULL,
                undoneAt INTEGER,
                FOREIGN KEY(sessionId) REFERENCES scan_sessions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_attempts_sessionId ON scan_attempts(sessionId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_attempts_copyId ON scan_attempts(copyId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_scan_attempts_attemptedAt " +
                "ON scan_attempts(attemptedAt)",
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS wishlist_items (
                editionId TEXT NOT NULL PRIMARY KEY,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                FOREIGN KEY(editionId) REFERENCES book_editions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_wishlist_items_status ON wishlist_items(status)",
        )
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE owned_copies ADD COLUMN copyLabel TEXT NOT NULL DEFAULT '所蔵本'",
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE owned_copies ADD COLUMN shelfOrderKey TEXT")
        db.execSQL(
            """
            UPDATE owned_copies
            SET shelfOrderKey = printf('%016x', addedAt) || lower(hex(id))
            WHERE tierId IS NOT NULL
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_owned_copies_tierId_shelfOrderKey " +
                "ON owned_copies(tierId, shelfOrderKey)",
        )
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_rooms (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_location_rooms_name " +
                "ON location_rooms(name)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_shelves (
                id TEXT NOT NULL PRIMARY KEY,
                roomId TEXT NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(roomId) REFERENCES location_rooms(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_location_shelves_roomId " +
                "ON location_shelves(roomId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_location_shelves_roomId_name " +
                "ON location_shelves(roomId, name)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_tiers (
                id TEXT NOT NULL PRIMARY KEY,
                shelfId TEXT NOT NULL,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                FOREIGN KEY(shelfId) REFERENCES location_shelves(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_location_tiers_shelfId " +
                "ON location_tiers(shelfId)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_location_tiers_shelfId_name " +
                "ON location_tiers(shelfId, name)",
        )
        db.execSQL(
            """
            CREATE TABLE owned_copies_new (
                id TEXT NOT NULL PRIMARY KEY,
                editionId TEXT NOT NULL,
                mediaType TEXT NOT NULL,
                location TEXT NOT NULL,
                readingStatus TEXT NOT NULL,
                addedAt INTEGER NOT NULL,
                tierId TEXT,
                FOREIGN KEY(editionId) REFERENCES book_editions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(tierId) REFERENCES location_tiers(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO owned_copies_new (
                id, editionId, mediaType, location, readingStatus, addedAt, tierId
            )
            SELECT id, editionId, mediaType, location, readingStatus, addedAt, NULL
            FROM owned_copies
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE owned_copies")
        db.execSQL("ALTER TABLE owned_copies_new RENAME TO owned_copies")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_owned_copies_editionId ON owned_copies(editionId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_owned_copies_tierId ON owned_copies(tierId)",
        )
    }
}
