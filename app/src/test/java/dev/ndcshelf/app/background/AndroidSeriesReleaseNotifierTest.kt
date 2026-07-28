package dev.ndcshelf.app.background

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.domain.repository.SeriesReleaseNotification
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AndroidSeriesReleaseNotifierTest {
    @Test
    fun disabledChannelDoesNotMarkCandidatesAsDelivered() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                SERIES_RELEASE_CHANNEL_ID,
                "シリーズの新刊候補",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        val notification = SeriesReleaseNotification(
            seriesId = "series",
            seriesTitle = "年代記",
            candidateIds = listOf("candidate"),
            candidateTitles = listOf("年代記 2"),
        )

        val delivered = AndroidSeriesReleaseNotifier(context).post(listOf(notification))

        assertTrue(delivered.isEmpty())
    }
}
