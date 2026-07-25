package dev.ndcshelf.app

import android.app.Application
import androidx.room.Room
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
        "ndc-shelf.db",
    ).build()

    val libraryRepository: LibraryRepository = DefaultLibraryRepository(
        database = database,
        metadataService = NdlBookMetadataService(),
    )
}
