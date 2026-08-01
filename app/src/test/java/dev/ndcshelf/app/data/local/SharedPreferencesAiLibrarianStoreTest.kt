package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.domain.ai.AiLibrarianDayKey
import dev.ndcshelf.app.domain.ai.AiLibrarianField
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianIntent
import dev.ndcshelf.app.domain.ai.AiLibrarianLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPreferencesAiLibrarianStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun usageCountIsScopedToTheCalendarDay() {
        val store = SharedPreferencesAiLibrarianStore(context)
        store.clearUsage()

        store.recordUse("2026-07-29")
        store.recordUse("2026-07-29")

        assertEquals(2, store.usedCount("2026-07-29"))
        assertEquals("日付が変われば自動的にリセットされる", 0, store.usedCount("2026-07-30"))
    }

    @Test
    fun usageCanBeReset() {
        val store = SharedPreferencesAiLibrarianStore(context)
        store.recordUse("2026-07-29")

        store.clearUsage()

        assertEquals(0, store.usedCount("2026-07-29"))
    }

    @Test
    fun historyRoundTripsAndKeepsNewestFirst() {
        val store = SharedPreferencesAiLibrarianStore(context)
        store.clearHistory()

        store.append(entry("q1", 1L))
        store.append(entry("q2", 2L))

        val reloaded = SharedPreferencesAiLibrarianStore(context).load()
        assertEquals(listOf("q2", "q1"), reloaded.map(AiLibrarianHistoryEntry::question))
        assertEquals(AiLibrarianIntent.PICK_NEXT, reloaded.first().intent)
        assertEquals(listOf("匿名サンプル図書A"), reloaded.first().referencedTitles)
    }

    @Test
    fun historyIsCappedAtTheConfiguredLimit() {
        val store = SharedPreferencesAiLibrarianStore(context)
        store.clearHistory()

        repeat(AiLibrarianLimits.MAX_HISTORY_ENTRIES + 5) { index ->
            store.append(entry("q$index", index.toLong()))
        }

        assertEquals(AiLibrarianLimits.MAX_HISTORY_ENTRIES, store.load().size)
    }

    @Test
    fun clearHistoryRemovesEverything() {
        val store = SharedPreferencesAiLibrarianStore(context)
        store.append(entry("q1", 1L))

        store.clearHistory()

        assertTrue(store.load().isEmpty())
        assertTrue(SharedPreferencesAiLibrarianStore(context).load().isEmpty())
    }

    @Test
    fun dayKeyUsesTheProvidedTimeZone() {
        val millis = 1_753_000_000_000L

        assertEquals(
            AiLibrarianDayKey.of(millis, TimeZone.getTimeZone("UTC")),
            AiLibrarianDayKey.of(millis, TimeZone.getTimeZone("UTC")),
        )
    }

    private fun entry(
        question: String,
        askedAtMillis: Long,
    ): AiLibrarianHistoryEntry =
        AiLibrarianHistoryEntry(
            id = "entry-$question",
            askedAtMillis = askedAtMillis,
            question = question,
            intent = AiLibrarianIntent.PICK_NEXT,
            itemCount = 1,
            includedFields = listOf(AiLibrarianField.TITLE),
            referencedTitles = listOf("匿名サンプル図書A"),
        )
}
