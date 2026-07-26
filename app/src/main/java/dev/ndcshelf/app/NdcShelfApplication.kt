package dev.ndcshelf.app

import android.app.Application
import androidx.room.Room
import dev.ndcshelf.app.data.backup.RoomDatabaseBackupManager
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.remote.NdlBookMetadataService
import dev.ndcshelf.app.data.repository.DefaultLibraryRepository
import dev.ndcshelf.app.domain.repository.LibraryRepository

class NdcShelfApplication : Application() {
    val container: AppContainer by lazy {
        AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val database = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME,
    ).build()

    val libraryRepository: LibraryRepository = DefaultLibraryRepository(
        database = database,
        metadataService = NdlBookMetadataService(),
    )

    val databaseBackupManager = RoomDatabaseBackupManager(
        context = application,
        database = database,
        automaticBackupDirectory = application.noBackupFilesDir.resolve("restore-backups"),
        appVersion = BuildConfig.VERSION_NAME,
    )
}
