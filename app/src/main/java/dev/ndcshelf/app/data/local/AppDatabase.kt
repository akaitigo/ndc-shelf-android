package dev.ndcshelf.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val APP_DATABASE_VERSION = 4

@Database(
    entities = [
        BookWorkEntity::class,
        BookEditionEntity::class,
        LocationRoomEntity::class,
        LocationShelfEntity::class,
        LocationTierEntity::class,
        OwnedCopyEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "ndc-shelf.db"

        // Register every manual migration here so production and migration tests use one graph.
        val MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }

    abstract fun libraryDao(): LibraryDao

    abstract fun locationDao(): LocationDao
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
