package dev.ndcshelf.app.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.ndcshelf.app.MainActivity
import dev.ndcshelf.app.NdcShelfApplication
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.repository.SeriesReleaseNotification
import dev.ndcshelf.app.domain.repository.SeriesWatchCheckResult
import dev.ndcshelf.app.domain.repository.SeriesWatchRepository
import dev.ndcshelf.app.domain.repository.SeriesWatchScheduler
import java.util.concurrent.TimeUnit

class AndroidSeriesWatchScheduler(
    context: Context,
) : SeriesWatchScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun reconcile(enabled: Boolean) {
        if (enabled) {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                buildSeriesWatchWorkRequest(),
            )
        } else {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}

internal fun buildSeriesWatchWorkRequest(): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<SeriesReleaseWatchWorker>(7, TimeUnit.DAYS, 1, TimeUnit.DAYS)
        .setConstraints(
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build(),
        ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
        .addTag(SERIES_WATCH_WORK_TAG)
        .build()

class SeriesReleaseWatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as NdcShelfApplication).container
        val runner =
            SeriesWatchRunner(
                container.seriesWatchRepository,
                container.seriesReleaseNotifier,
                container.consentRepository,
            )
        return when (runner.run()) {
            SeriesWatchRunResult.SUCCESS -> Result.success()
            SeriesWatchRunResult.RETRY -> Result.retry()
        }
    }
}

internal class SeriesWatchRunner(
    private val repository: SeriesWatchRepository,
    private val notifier: SeriesReleaseNotifier,
    private val consentRepository: ConsentRepository,
) {
    suspend fun run(): SeriesWatchRunResult {
        // 同意なし・撤回後は外部通信を一切行わない（fail-closed）
        if (!consentRepository.isGranted(ConsentPurpose.SERIES_RELEASE_WATCH)) {
            return SeriesWatchRunResult.SUCCESS
        }
        val check = repository.checkEnabledWatches()
        val notifications =
            when (check) {
                is SeriesWatchCheckResult.Success -> check.notifications
                is SeriesWatchCheckResult.PartialFailure -> check.notifications
            }
        repository.markNotified(notifier.post(notifications).toList())
        return if (check is SeriesWatchCheckResult.PartialFailure && check.retryable) {
            SeriesWatchRunResult.RETRY
        } else {
            SeriesWatchRunResult.SUCCESS
        }
    }
}

internal enum class SeriesWatchRunResult { SUCCESS, RETRY }

fun interface SeriesReleaseNotifier {
    fun post(notifications: List<SeriesReleaseNotification>): Set<String>
}

class AndroidSeriesReleaseNotifier(
    private val context: Context,
) : SeriesReleaseNotifier {
    override fun post(notifications: List<SeriesReleaseNotification>): Set<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptySet()
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return emptySet()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(SERIES_RELEASE_CHANNEL_ID)
                ?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return emptySet()
        }
        val manager = NotificationManagerCompat.from(context)
        val delivered = linkedSetOf<String>()
        notifications.forEach { item ->
            if (item.candidateIds.isEmpty()) return@forEach
            val openApp =
                PendingIntent.getActivity(
                    context,
                    item.seriesId.hashCode(),
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val style = NotificationCompat.InboxStyle()
            item.candidateTitles.take(MAX_NOTIFICATION_TITLES).forEach(style::addLine)
            val notification =
                NotificationCompat
                    .Builder(context, SERIES_RELEASE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(
                        context.getString(
                            R.string.series_watch_notification_title,
                            item.seriesTitle,
                        ),
                    )
                    .setContentText(
                        context.resources.getQuantityString(
                            R.plurals.series_watch_notification_text,
                            item.candidateIds.size,
                            item.candidateIds.size,
                        ),
                    )
                    .setStyle(style)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        NotificationCompat
                            .Builder(context, SERIES_RELEASE_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_launcher)
                            .setContentTitle(
                                context.getString(
                                    R.string.series_watch_notification_group_title,
                                ),
                            )
                            .setContentText(
                                context.getString(
                                    R.string.series_watch_notification_group_text,
                                ),
                            )
                            .build(),
                    ).build()
            runCatching { manager.notify(item.seriesId.hashCode(), notification) }
                .onSuccess { delivered += item.candidateIds }
        }
        return delivered
    }

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                SERIES_RELEASE_CHANNEL_ID,
                context.getString(R.string.series_watch_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description =
                    context.getString(R.string.series_watch_notification_channel_description)
            }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

internal const val UNIQUE_WORK_NAME = "series-release-watch"
internal const val SERIES_WATCH_WORK_TAG = "series-release-watch-periodic"
internal const val SERIES_RELEASE_CHANNEL_ID = "series_release_candidates"
private const val MAX_NOTIFICATION_TITLES = 5
