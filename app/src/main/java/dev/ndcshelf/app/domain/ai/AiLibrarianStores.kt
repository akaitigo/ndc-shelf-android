package dev.ndcshelf.app.domain.ai

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 1日あたりの質問回数（＝費用上限）の記録。日付キーは端末のタイムゾーンで
 * 求めた暦日で、キーが変わった時点で自動的にリセットされる。
 */
interface AiLibrarianUsageStore {
    fun usedCount(dayKey: String): Int

    fun recordUse(dayKey: String)

    fun clearUsage()
}

/**
 * 質問履歴。端末内のアプリ専用領域だけに保存し、外部送信・OSバックアップの
 * 対象にしない。利用者はいつでも全件削除できる。
 */
interface AiLibrarianHistoryStore {
    fun load(): List<AiLibrarianHistoryEntry>

    fun append(entry: AiLibrarianHistoryEntry): List<AiLibrarianHistoryEntry>

    fun clearHistory()
}

@Serializable
data class AiLibrarianHistoryEntry(
    val id: String,
    val askedAtMillis: Long,
    val question: String,
    val intent: AiLibrarianIntent,
    val itemCount: Int,
    val includedFields: List<AiLibrarianField>,
    val referencedTitles: List<String>,
)

/** 暦日キー（yyyy-MM-dd）。上限判定と履歴表示で共通に使う。 */
object AiLibrarianDayKey {
    fun of(
        millis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(millis))
}

/** テストと未設定環境で使う揮発実装。 */
class InMemoryAiLibrarianUsageStore : AiLibrarianUsageStore {
    private val counts = mutableMapOf<String, Int>()

    override fun usedCount(dayKey: String): Int = counts[dayKey] ?: 0

    override fun recordUse(dayKey: String) {
        counts[dayKey] = usedCount(dayKey) + 1
    }

    override fun clearUsage() {
        counts.clear()
    }
}

/** テストと未設定環境で使う揮発実装。 */
class InMemoryAiLibrarianHistoryStore : AiLibrarianHistoryStore {
    private var entries: List<AiLibrarianHistoryEntry> = emptyList()

    override fun load(): List<AiLibrarianHistoryEntry> = entries

    override fun append(entry: AiLibrarianHistoryEntry): List<AiLibrarianHistoryEntry> {
        entries = (listOf(entry) + entries).take(AiLibrarianLimits.MAX_HISTORY_ENTRIES)
        return entries
    }

    override fun clearHistory() {
        entries = emptyList()
    }
}
