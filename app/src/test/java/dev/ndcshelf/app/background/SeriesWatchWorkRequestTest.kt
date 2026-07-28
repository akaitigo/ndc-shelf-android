package dev.ndcshelf.app.background

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SeriesWatchWorkRequestTest {
    @Test
    fun periodicRequestIsUniqueFriendlyBatteryAwareAndExponentiallyBackedOff() {
        val request = buildSeriesWatchWorkRequest()
        val spec = request.workSpec

        assertEquals(TimeUnit.DAYS.toMillis(7), spec.intervalDuration)
        assertEquals(TimeUnit.DAYS.toMillis(1), spec.flexDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.HOURS.toMillis(1), spec.backoffDelayDuration)
        assertTrue(SERIES_WATCH_WORK_TAG in request.tags)
    }
}
