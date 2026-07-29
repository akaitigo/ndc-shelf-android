package dev.ndcshelf.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.ndcshelf.app.background.AndroidSeriesReleaseNotifier
import dev.ndcshelf.app.background.AndroidSeriesWatchScheduler
import dev.ndcshelf.app.data.backup.RoomDatabaseBackupManager
import dev.ndcshelf.app.data.consent.RoomConsentRepository
import dev.ndcshelf.app.data.diagnostics.DiagnosticsLoggingBookMetadataService
import dev.ndcshelf.app.data.diagnostics.FileDiagnosticsLogger
import dev.ndcshelf.app.data.diagnostics.RoomDiagnosticsSnapshotCollector
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SharedPreferencesInsightsExclusionStore
import dev.ndcshelf.app.data.local.SharedPreferencesLibrarySearchSettingsStore
import dev.ndcshelf.app.data.remote.NdlBookMetadataService
import dev.ndcshelf.app.data.remote.NdlSeriesReleaseService
import dev.ndcshelf.app.data.repository.DefaultLibraryRepository
import dev.ndcshelf.app.data.repository.RoomLocationRepository
import dev.ndcshelf.app.data.repository.RoomReadingHistoryRepository
import dev.ndcshelf.app.data.repository.RoomSeriesRepository
import dev.ndcshelf.app.data.repository.RoomSeriesWatchRepository
import dev.ndcshelf.app.data.repository.RoomWorkGroupRepository
import dev.ndcshelf.app.data.sync.RoomSyncDomainStore
import dev.ndcshelf.app.data.sync.RoomSyncEngine
import dev.ndcshelf.app.data.sync.RoomSyncStatusRepository
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger
import dev.ndcshelf.app.domain.insights.InsightsExclusionStore
import dev.ndcshelf.app.domain.network.NdlEndpointPolicy
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.LocationRepository
import dev.ndcshelf.app.domain.repository.ReadingHistoryRepository
import dev.ndcshelf.app.domain.repository.SeriesRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchScheduler
import dev.ndcshelf.app.domain.repository.WorkGroupRepository
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.util.concurrent.TimeUnit

class NdcShelfApplication :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {
    val container: AppContainer by lazy {
        AppContainer(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = createNdlCoverImageLoader(context)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

internal fun createNdlCoverImageLoader(context: Context): ImageLoader =
    ImageLoader
        .Builder(context)
        .memoryCache {
            MemoryCache
                .Builder()
                .maxSizePercent(context, 0.10)
                .build()
        }.diskCache {
            DiskCache
                .Builder()
                .directory(context.cacheDir.resolve("ndl-cover-cache").toOkioPath())
                .maxSizeBytes(NDL_COVER_DISK_CACHE_BYTES)
                .build()
        }.components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = {
                        OkHttpClient
                            .Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .callTimeout(15, TimeUnit.SECONDS)
                            .followRedirects(false)
                            .retryOnConnectionFailure(false)
                            .addInterceptor { chain ->
                                val request = chain.request()
                                if (!NdlEndpointPolicy.isAllowedCoverUrl(request.url.toString())) {
                                    throw IOException("Blocked non-NDL cover URL")
                                }
                                chain.proceed(
                                    request
                                        .newBuilder()
                                        .header("Accept", "image/*")
                                        .header(
                                            "User-Agent",
                                            "NDC-Shelf/${BuildConfig.VERSION_NAME} (Android)",
                                        ).build(),
                                )
                            }.build()
                    },
                ),
            )
        }.build()

internal const val NDL_COVER_DISK_CACHE_BYTES = 50L * 1024 * 1024

class AppContainer(
    application: Application,
) {
    private val database =
        Room
            .databaseBuilder(
                application,
                AppDatabase::class.java,
                AppDatabase.DATABASE_NAME,
            ).addMigrations(*AppDatabase.MIGRATIONS.toTypedArray())
            .build()

    val syncEngine =
        RoomSyncEngine(
            database,
            RoomSyncDomainStore(database),
        )

    val syncStatusRepository = RoomSyncStatusRepository(database)

    val diagnosticsLogger: DiagnosticsLogger =
        FileDiagnosticsLogger(application.noBackupFilesDir.resolve("diagnostics"))

    val libraryRepository: LibraryRepository =
        DefaultLibraryRepository(
            database = database,
            metadataService =
                DiagnosticsLoggingBookMetadataService(
                    delegate = NdlBookMetadataService(),
                    logger = diagnosticsLogger,
                ),
            syncJournal = syncEngine,
        )

    val locationRepository: LocationRepository = RoomLocationRepository(database, syncJournal = syncEngine)

    val readingHistoryRepository: ReadingHistoryRepository =
        RoomReadingHistoryRepository(database, syncJournal = syncEngine)

    val seriesRepository: SeriesRepository = RoomSeriesRepository(database, syncJournal = syncEngine)

    val workGroupRepository: WorkGroupRepository = RoomWorkGroupRepository(database, syncJournal = syncEngine)

    val seriesWatchRepository: SeriesWatchRepository =
        RoomSeriesWatchRepository(
            database = database,
            source = NdlSeriesReleaseService(),
        )

    val seriesWatchScheduler: SeriesWatchScheduler = AndroidSeriesWatchScheduler(application)

    val seriesReleaseNotifier = AndroidSeriesReleaseNotifier(application)

    val consentRepository: ConsentRepository = RoomConsentRepository(database)

    val diagnosticsSnapshotCollector =
        RoomDiagnosticsSnapshotCollector(
            database = database,
            consentRepository = consentRepository,
            logger = diagnosticsLogger,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidSdkInt = android.os.Build.VERSION.SDK_INT,
        )

    val librarySearchSettings = SharedPreferencesLibrarySearchSettingsStore(application)

    val insightsExclusionStore: InsightsExclusionStore =
        SharedPreferencesInsightsExclusionStore(application)

    val databaseBackupManager =
        RoomDatabaseBackupManager(
            context = application,
            database = database,
            automaticBackupDirectory = application.noBackupFilesDir.resolve("restore-backups"),
            appVersion = BuildConfig.VERSION_NAME,
        )
}
