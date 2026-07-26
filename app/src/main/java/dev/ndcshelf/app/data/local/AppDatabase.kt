package dev.ndcshelf.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

const val APP_DATABASE_VERSION = 1

@Database(
    entities = [
        BookWorkEntity::class,
        BookEditionEntity::class,
        OwnedCopyEntity::class,
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_NAME = "ndc-shelf.db"

        // Register every manual migration here so production and migration tests use one graph.
        val MIGRATIONS: List<Migration> = emptyList()
    }

    abstract fun libraryDao(): LibraryDao
}
