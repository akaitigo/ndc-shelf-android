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
import dev.ndcshelf.app.background.AndroidLibrarySyncScheduler
import dev.ndcshelf.app.background.AndroidSeriesReleaseNotifier
import dev.ndcshelf.app.background.AndroidSeriesWatchScheduler
import dev.ndcshelf.app.data.backup.RoomDatabaseBackupManager
import dev.ndcshelf.app.data.consent.RoomConsentRepository
import dev.ndcshelf.app.data.diagnostics.DiagnosticsLlmTelemetrySink
import dev.ndcshelf.app.data.diagnostics.DiagnosticsLoggingBookMetadataService
import dev.ndcshelf.app.data.diagnostics.DiagnosticsLoggingLlmModelStore
import dev.ndcshelf.app.data.diagnostics.FileDiagnosticsLogger
import dev.ndcshelf.app.data.diagnostics.RoomDiagnosticsSnapshotCollector
import dev.ndcshelf.app.data.local.AndroidLlmDeviceProbe
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.FileLlmModelStore
import dev.ndcshelf.app.data.local.SharedPreferencesAiLibrarianStore
import dev.ndcshelf.app.data.local.SharedPreferencesInsightsExclusionStore
import dev.ndcshelf.app.data.local.SharedPreferencesLibrarySearchSettingsStore
import dev.ndcshelf.app.data.remote.NdlBookMetadataService
import dev.ndcshelf.app.data.remote.NdlSeriesReleaseService
import dev.ndcshelf.app.data.repository.DefaultLibraryRepository
import dev.ndcshelf.app.data.repository.RoomLocationRepository
import dev.ndcshelf.app.data.repository.RoomReadingHistoryRepository
import dev.ndcshelf.app.data.repository.RoomSeriesRepository
import dev.ndcshelf.app.data.repository.RoomSeriesWatchRepository
import dev.ndcshelf.app.data.repository.RoomTagRepository
import dev.ndcshelf.app.data.repository.RoomWorkGroupRepository
import dev.ndcshelf.app.data.sync.E2eeSyncCoordinator
import dev.ndcshelf.app.data.sync.RoomSyncDomainStore
import dev.ndcshelf.app.data.sync.RoomSyncEngine
import dev.ndcshelf.app.data.sync.RoomSyncStatusRepository
import dev.ndcshelf.app.data.sync.backend.AndroidSyncBackendFactory
import dev.ndcshelf.app.data.sync.crypto.AndroidKeystoreSyncKeyManager
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryStore
import dev.ndcshelf.app.domain.ai.AiLibrarianProvider
import dev.ndcshelf.app.domain.ai.AiLibrarianUsageStore
import dev.ndcshelf.app.domain.ai.OnDeviceHeuristicLibrarian
import dev.ndcshelf.app.domain.ai.llm.FallbackAiLibrarianProvider
import dev.ndcshelf.app.data.llm.PlatformLlmRuntime
import dev.ndcshelf.app.domain.ai.llm.LlmCapability
import dev.ndcshelf.app.domain.ai.llm.LlmCapabilityChecker
import dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog
import dev.ndcshelf.app.domain.ai.llm.LlmModelStore
import dev.ndcshelf.app.domain.ai.llm.LlmTelemetrySink
import dev.ndcshelf.app.domain.ai.llm.OnDeviceLlmLibrarian
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger
import dev.ndcshelf.app.domain.insights.InsightsExclusionStore
import dev.ndcshelf.app.domain.network.NdlEndpointPolicy
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.LocationRepository
import dev.ndcshelf.app.domain.repository.ReadingHistoryRepository
import dev.ndcshelf.app.domain.repository.SeriesRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchScheduler
import dev.ndcshelf.app.domain.repository.TagRepository
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

    val librarySyncScheduler = AndroidLibrarySyncScheduler(application)

    val syncCoordinator by lazy {
        E2eeSyncCoordinator(
            database = database,
            engine = syncEngine,
            keyManager = AndroidKeystoreSyncKeyManager(),
            backendFactory = AndroidSyncBackendFactory(application),
            consentRepository = consentRepository,
            scheduler = librarySyncScheduler,
        )
    }

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

    val tagRepository: TagRepository = RoomTagRepository(database, syncJournal = syncEngine)

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

    /** 端末内LLMのモデル配置。OSクラウドbackup・D2D対象外のnoBackup領域へ置く。 */
    val llmModelStore: LlmModelStore =
        DiagnosticsLoggingLlmModelStore(
            delegate = FileLlmModelStore(application.noBackupFilesDir.resolve("llm-models")),
            logger = diagnosticsLogger,
        )

    private val llmDeviceProbe =
        AndroidLlmDeviceProbe(
            context = application,
            runtimeAvailable = PlatformLlmRuntime::isAvailable,
        )

    /** 端末内LLMの状態。UIはここだけを見て取得導線と縮退表示を切り替える。 */
    fun llmCapability(): LlmCapability =
        LlmCapabilityChecker.evaluate(
            profile = llmDeviceProbe.profile(),
            model = LlmModelCatalog.defaultModel,
            enabled = true,
        )

    private val llmTelemetrySink: LlmTelemetrySink = DiagnosticsLlmTelemetrySink(diagnosticsLogger)

    /**
     * 端末内LLMのAI司書プロバイダ。台帳（[LlmModelCatalog]）に承認済みモデルが
     * 無い、または端末条件を満たさない場合は起動せず、規則ベースの
     * [OnDeviceHeuristicLibrarian]へ縮退する（docs/adr/0009-on-device-llm-librarian.md）。
     */
    private val onDeviceLlmLibrarian =
        OnDeviceLlmLibrarian(
            capabilityProvider = ::llmCapability,
            modelStore = llmModelStore,
            runtime = PlatformLlmRuntime.runtime,
            runtimeCacheDir = application.cacheDir.resolve("llm-runtime"),
            telemetry = llmTelemetrySink,
        )

    /**
     * AI司書のプロバイダ。端末内LLMを第一候補にし、非対応端末・モデル未取得・
     * 初期化失敗・出力検証失敗では規則ベース実装の検証済み回答へ縮退する。
     * どちらの経路も端末外へデータを送信しない（docs/adr/0007・0009）。
     */
    val aiLibrarianProvider: AiLibrarianProvider =
        FallbackAiLibrarianProvider(
            preferred = onDeviceLlmLibrarian,
            fallback = OnDeviceHeuristicLibrarian(),
            preferredEnabled = { llmCapability() is LlmCapability.Supported },
            onDegraded = { diagnosticsLogger.log(DiagnosticCode.LLM_DEGRADED_TO_HEURISTIC) },
        )

    private val aiLibrarianStore = SharedPreferencesAiLibrarianStore(application)

    val aiLibrarianUsageStore: AiLibrarianUsageStore = aiLibrarianStore

    val aiLibrarianHistoryStore: AiLibrarianHistoryStore = aiLibrarianStore

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
