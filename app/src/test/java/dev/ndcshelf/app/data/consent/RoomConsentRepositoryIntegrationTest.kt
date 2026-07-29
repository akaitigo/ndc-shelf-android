package dev.ndcshelf.app.data.consent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomConsentRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private var now = 1_700_000_000_000L
    private lateinit var repository: RoomConsentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomConsentRepository(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun defaultIsNotGrantedForEveryPurpose() =
        runBlocking {
            ConsentPurpose.entries.forEach { purpose ->
                assertFalse(repository.isGranted(purpose))
            }
            assertTrue(repository.observeConsents().first().isEmpty())
        }

    @Test
    fun grantRecordsVersionAndTimestamp() =
        runBlocking {
            val record = repository.grant(ConsentPurpose.SERIES_RELEASE_WATCH)

            assertTrue(repository.isGranted(ConsentPurpose.SERIES_RELEASE_WATCH))
            assertEquals(ConsentPurpose.SERIES_RELEASE_WATCH.policyVersion, record.consentedVersion)
            assertEquals(now, record.grantedAtMillis)
            assertFalse(repository.isGranted(ConsentPurpose.LIBRARY_SYNC))
        }

    @Test
    fun revokeStopsGrantAndKeepsEvidence() =
        runBlocking {
            repository.grant(ConsentPurpose.SERIES_RELEASE_WATCH)
            now += 60_000

            val revoked = repository.revoke(ConsentPurpose.SERIES_RELEASE_WATCH)

            assertNotNull(revoked)
            assertEquals(now, requireNotNull(revoked).revokedAtMillis)
            assertFalse(repository.isGranted(ConsentPurpose.SERIES_RELEASE_WATCH))
            val observed =
                repository
                    .observeConsents()
                    .first()
                    .getValue(ConsentPurpose.SERIES_RELEASE_WATCH)
            assertNotNull(observed.revokedAtMillis)
        }

    @Test
    fun regrantAfterRevocationIsEffective() =
        runBlocking {
            repository.grant(ConsentPurpose.SERIES_RELEASE_WATCH)
            repository.revoke(ConsentPurpose.SERIES_RELEASE_WATCH)

            repository.grant(ConsentPurpose.SERIES_RELEASE_WATCH)

            assertTrue(repository.isGranted(ConsentPurpose.SERIES_RELEASE_WATCH))
        }

    @Test
    fun outdatedPolicyVersionRequiresReconsent() {
        val outdated =
            ConsentRecord(
                purpose = ConsentPurpose.SERIES_RELEASE_WATCH,
                consentedVersion = ConsentPurpose.SERIES_RELEASE_WATCH.policyVersion - 1,
                grantedAtMillis = now,
                revokedAtMillis = null,
            )

        assertFalse(outdated.granted)
    }
}
