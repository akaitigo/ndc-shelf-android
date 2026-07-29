package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesInsightsExclusionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearPreferences() {
        context
            .getSharedPreferences("insights-exclusions", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun exclusionsSurviveStoreRecreation() =
        runTest {
            val store = SharedPreferencesInsightsExclusionStore(context)
            store.exclude("copy-1")
            store.exclude("copy-2")
            store.exclude("copy-1")

            val reloaded = SharedPreferencesInsightsExclusionStore(context)

            assertEquals(setOf("copy-1", "copy-2"), reloaded.observeExcludedCopyIds().first())
        }

    @Test
    fun observeEmitsUpdatesAfterExcludeAndClear() =
        runTest {
            val store = SharedPreferencesInsightsExclusionStore(context)

            assertEquals(emptySet<String>(), store.observeExcludedCopyIds().first())

            store.exclude("copy-1")
            assertEquals(setOf("copy-1"), store.observeExcludedCopyIds().first())

            store.clear()
            assertEquals(emptySet<String>(), store.observeExcludedCopyIds().first())
        }

    @Test
    fun analysisResetClearsPersistedEntriesForNewInstances() =
        runTest {
            SharedPreferencesInsightsExclusionStore(context).exclude("copy-1")

            SharedPreferencesInsightsExclusionStore(context).clear()

            assertEquals(
                emptySet<String>(),
                SharedPreferencesInsightsExclusionStore(context).observeExcludedCopyIds().first(),
            )
        }

    @Test
    fun excludeStopsAcceptingNewEntriesAtTheCap() =
        runTest {
            val store = SharedPreferencesInsightsExclusionStore(context)
            repeat(SharedPreferencesInsightsExclusionStore.MAX_ENTRIES) { index ->
                store.exclude("copy-$index")
            }

            store.exclude("copy-overflow")

            val excluded = store.observeExcludedCopyIds().first()
            assertEquals(SharedPreferencesInsightsExclusionStore.MAX_ENTRIES, excluded.size)
            assertEquals(false, "copy-overflow" in excluded)
        }
}
