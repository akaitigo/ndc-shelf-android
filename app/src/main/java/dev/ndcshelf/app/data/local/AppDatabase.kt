package dev.ndcshelf.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

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
    }

    abstract fun libraryDao(): LibraryDao
}
