package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.core.content.edit
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryStore
import dev.ndcshelf.app.domain.ai.AiLibrarianLimits
import dev.ndcshelf.app.domain.ai.AiLibrarianUsageStore
import kotlinx.serialization.json.Json

/**
 * AI司書の1日あたり利用回数と質問履歴を、アプリ専用SharedPreferencesへ保存する。
 *
 * Roomのschema変更を伴わない端末内設定として保持し、エクスポート・完全backup・
 * 同期・OSのクラウドbackupの対象へ含めない。履歴は上限件数で自動的に切り詰め、
 * 「質問履歴をすべて削除」で即時に全消去できる。
 */
class SharedPreferencesAiLibrarianStore(
    context: Context,
) : AiLibrarianUsageStore,
    AiLibrarianHistoryStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun usedCount(dayKey: String): Int =
        if (preferences.getString(KEY_USAGE_DAY, null) == dayKey) {
            preferences.getInt(KEY_USAGE_COUNT, 0)
        } else {
            0
        }

    override fun recordUse(dayKey: String) {
        val next = usedCount(dayKey) + 1
        preferences.edit {
            putString(KEY_USAGE_DAY, dayKey)
            putInt(KEY_USAGE_COUNT, next)
        }
    }

    override fun clearUsage() {
        preferences.edit {
            remove(KEY_USAGE_DAY)
            remove(KEY_USAGE_COUNT)
        }
    }

    override fun load(): List<AiLibrarianHistoryEntry> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AiLibrarianHistoryEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    override fun append(entry: AiLibrarianHistoryEntry): List<AiLibrarianHistoryEntry> {
        val next = (listOf(entry) + load()).take(AiLibrarianLimits.MAX_HISTORY_ENTRIES)
        preferences.edit { putString(KEY_HISTORY, json.encodeToString(next)) }
        return next
    }

    override fun clearHistory() {
        preferences.edit { remove(KEY_HISTORY) }
    }

    private companion object {
        const val PREFERENCES_NAME = "ai-librarian"
        const val KEY_USAGE_DAY = "usage-day"
        const val KEY_USAGE_COUNT = "usage-count"
        const val KEY_HISTORY = "history"

        val json = Json { ignoreUnknownKeys = true }
    }
}
