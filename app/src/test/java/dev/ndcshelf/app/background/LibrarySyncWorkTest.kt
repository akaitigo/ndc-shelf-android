package dev.ndcshelf.app.background

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.sync.SyncActionResult
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncFailure
import dev.ndcshelf.app.domain.sync.SyncFailureReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LibrarySyncWorkTest {
    @Test
    fun periodicRequestIsNetworkAndBatteryConstrainedWithExponentialBackoff() {
        val request = buildLibrarySyncWorkRequest()
        val spec = request.workSpec

        assertEquals(TimeUnit.DAYS.toMillis(1), spec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(6), spec.flexDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.HOURS.toMillis(1), spec.backoffDelayDuration)
        assertTrue(LIBRARY_SYNC_WORK_TAG in request.tags)
    }

    @Test
    fun runnerDoesNotStartSyncWithoutConsent() =
        runBlocking {
            var invoked = false
            val runner =
                LibrarySyncRunner(consentRepository = FakeConsentRepository(granted = false)) {
                    invoked = true
                    SyncActionResult.Success()
                }

            // 同意なしではsyncNowを呼ばず、成功として終了する（fail-closed）。
            assertEquals(LibrarySyncRunResult.SUCCESS, runner.run())
            assertEquals(false, invoked)
        }

    @Test
    fun runnerRetriesOnlyRetryableFailures() =
        runBlocking {
            val retryable =
                LibrarySyncRunner(consentRepository = FakeConsentRepository(granted = true)) {
                    SyncActionResult.Failure(
                        SyncFailure(SyncFailureReason.BACKEND, SyncBackendErrorKind.IO_FAILURE),
                    )
                }
            assertEquals(LibrarySyncRunResult.RETRY, retryable.run())

            val permanent =
                LibrarySyncRunner(consentRepository = FakeConsentRepository(granted = true)) {
                    SyncActionResult.Failure(
                        SyncFailure(SyncFailureReason.BACKEND, SyncBackendErrorKind.PERMISSION_LOST),
                    )
                }
            assertEquals(LibrarySyncRunResult.SUCCESS, permanent.run())

            val succeeded =
                LibrarySyncRunner(consentRepository = FakeConsentRepository(granted = true)) {
                    SyncActionResult.Success(3)
                }
            assertEquals(LibrarySyncRunResult.SUCCESS, succeeded.run())
        }

    private class FakeConsentRepository(
        private val granted: Boolean,
    ) : ConsentRepository {
        override fun observeConsents(): Flow<Map<ConsentPurpose, ConsentRecord>> = flowOf(emptyMap())

        override suspend fun isGranted(purpose: ConsentPurpose): Boolean = granted

        override suspend fun grant(purpose: ConsentPurpose): ConsentRecord = throw UnsupportedOperationException()

        override suspend fun revoke(purpose: ConsentPurpose): ConsentRecord? = throw UnsupportedOperationException()
    }
}
