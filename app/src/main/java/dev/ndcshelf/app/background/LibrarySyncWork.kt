package dev.ndcshelf.app.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ndcshelf.app.NdcShelfApplication
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.sync.LibrarySyncScheduler
import dev.ndcshelf.app.domain.sync.SyncActionResult
import java.util.concurrent.TimeUnit

/**
 * opt-in時だけ登録する定期同期（SeriesWatchのschedulerパターン踏襲）。
 * workerは実行時にも同意と有効化をfail-closedで再検査し、同意なしでは
 * network・ファイル・鍵へ一切触れない。
 */
class AndroidLibrarySyncScheduler(
    context: Context,
) : LibrarySyncScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun reconcile(enabled: Boolean) {
        if (enabled) {
            workManager.enqueueUniquePeriodicWork(
                LIBRARY_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                buildLibrarySyncWorkRequest(),
            )
        } else {
            workManager.cancelUniqueWork(LIBRARY_SYNC_WORK_NAME)
        }
    }
}

internal fun buildLibrarySyncWorkRequest(): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<LibrarySyncWorker>(1, TimeUnit.DAYS, 6, TimeUnit.HOURS)
        .setConstraints(
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build(),
        ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
        .addTag(LIBRARY_SYNC_WORK_TAG)
        .build()

class LibrarySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NdcShelfApplication).container
        val runner =
            LibrarySyncRunner(
                consentRepository = container.consentRepository,
                // 同意検査を通過するまでcoordinatorを解決しない（鍵・backendへ触れない）。
                syncNow = { container.syncCoordinator.syncNow() },
            )
        return when (runner.run()) {
            LibrarySyncRunResult.SUCCESS -> Result.success()
            LibrarySyncRunResult.RETRY -> Result.retry()
        }
    }
}

internal class LibrarySyncRunner(
    private val consentRepository: ConsentRepository,
    private val syncNow: suspend () -> SyncActionResult,
) {
    suspend fun run(): LibrarySyncRunResult {
        // 同意なし・撤回後はbackend・鍵・fileへ触れない（fail-closed）。
        if (!consentRepository.isGranted(ConsentPurpose.LIBRARY_SYNC)) {
            return LibrarySyncRunResult.SUCCESS
        }
        return when (val result = syncNow()) {
            is SyncActionResult.Failure -> {
                if (result.failure.retryable) LibrarySyncRunResult.RETRY else LibrarySyncRunResult.SUCCESS
            }

            else -> {
                LibrarySyncRunResult.SUCCESS
            }
        }
    }
}

internal enum class LibrarySyncRunResult { SUCCESS, RETRY }

internal const val LIBRARY_SYNC_WORK_NAME = "library-e2ee-sync"
internal const val LIBRARY_SYNC_WORK_TAG = "library-e2ee-sync-periodic"
