package dev.ndcshelf.app.domain.search

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.NdcCategory
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagNameRules
import dev.ndcshelf.app.domain.text.UiMessage
import dev.ndcshelf.app.ui.text.labelRes
import java.util.Calendar
import java.util.TimeZone

/**
 * 検索クエリから導出した1つの解釈条件。ラベルはUIチップとしてプレーンテキスト表示し、
 * タグ名など信頼できない入力を含んでもテキスト以外として解釈しない。
 */
sealed interface SearchInterpretationChip {
    /** 解除操作の識別に使う安定キー。 */
    val id: String

    /** チップ表示用のテキストラベル。 */
    val label: UiMessage

    data class Status(
        val status: ReadingStatus,
    ) : SearchInterpretationChip {
        override val id: String get() = "status:${status.name}"
        override val label: UiMessage get() = UiMessage(status.labelRes)
    }

    data class Ndc(
        val category: NdcCategory,
    ) : SearchInterpretationChip {
        override val id: String get() = "ndc:${category.digit}"
        override val label: UiMessage
            get() =
                UiMessage(
                    R.string.nl_search_chip_ndc,
                    category.digit,
                    UiMessage(category.labelRes),
                )
    }

    data class Location(
        val locationQuery: String,
    ) : SearchInterpretationChip {
        override val id: String get() = "location:$locationQuery"
        override val label: UiMessage
            get() = UiMessage(R.string.nl_search_chip_location, locationQuery)
    }

    data class Added(
        val rangeLabel: String,
        val afterMillis: Long,
        val beforeMillis: Long,
    ) : SearchInterpretationChip {
        override val id: String get() = "added:$rangeLabel"
        override val label: UiMessage
            get() = UiMessage(R.string.nl_search_chip_added, rangeLabel)
    }

    data class TagRef(
        val tagId: String,
        val tagName: String,
    ) : SearchInterpretationChip {
        override val id: String get() = "tag:$tagId"
        override val label: UiMessage
            get() = UiMessage(R.string.nl_search_chip_tag, tagName)
    }
}

/** クエリ内で解釈に消費した範囲つきのチップ。解除時はこの範囲を通常検索語へ戻す。 */
data class InterpretedToken(
    val chip: SearchInterpretationChip,
    val range: IntRange,
)

/**
 * 自然言語クエリの解釈結果。chipsが空なら解釈なし（従来の部分一致検索へフォールバック）。
 * 解釈はすべて端末内で完結し、ネットワークへは何も送信しない。
 */
data class NaturalLanguageInterpretation(
    val source: String,
    val tokens: List<InterpretedToken>,
) {
    val chips: List<SearchInterpretationChip>
        get() = tokens.map(InterpretedToken::chip)

    /**
     * 解除されていないチップが消費した範囲を除いた残りの検索語。
     * 解除されたチップの範囲は通常の部分一致検索語として復元する。
     */
    fun residualQuery(dismissedChipIds: Set<String> = emptySet()): String {
        if (tokens.isEmpty()) return source
        val removed = BooleanArray(source.length)
        tokens
            .filterNot { token -> token.chip.id in dismissedChipIds }
            .forEach { token -> token.range.forEach { index -> removed[index] = true } }
        val remaining =
            buildString {
                source.forEachIndexed { index, char -> if (!removed[index]) append(char) }
            }
        return cleanUpResidual(remaining)
    }

    companion object {
        val NONE = NaturalLanguageInterpretation(source = "", tokens = emptyList())
    }
}

/**
 * 解釈を検索条件へ適用する。解釈が空なら条件を変更せず従来検索のまま返す。
 * 自然言語で導出した読書状態は、最新の入力意図として手動フィルタより優先する。
 */
fun LibrarySearchCriteria.applyInterpretation(
    interpretation: NaturalLanguageInterpretation,
    dismissedChipIds: Set<String> = emptySet(),
): LibrarySearchCriteria {
    if (interpretation.tokens.isEmpty()) return this
    var result = copy(query = interpretation.residualQuery(dismissedChipIds))
    interpretation.tokens
        .filterNot { token -> token.chip.id in dismissedChipIds }
        .forEach { token ->
            result =
                when (val chip = token.chip) {
                    is SearchInterpretationChip.Status -> {
                        result.copy(readingStatus = chip.status)
                    }

                    is SearchInterpretationChip.Ndc -> {
                        result.copy(ndcTopClass = chip.category.digit)
                    }

                    is SearchInterpretationChip.Location -> {
                        result.copy(locationQuery = chip.locationQuery)
                    }

                    is SearchInterpretationChip.Added -> {
                        result.copy(
                            addedAfterMillis = chip.afterMillis,
                            addedBeforeMillis = chip.beforeMillis,
                        )
                    }

                    is SearchInterpretationChip.TagRef -> {
                        result.copy(tagIds = result.tagIds + chip.tagId)
                    }
                }
        }
    return result
}

/**
 * 日本語クエリを端末内のルールだけで既存の検索条件へ変換する純粋関数。
 *
 * 優先規則（docs/NL_SEARCH.md）:
 * 1. 明示タグ指定（`#名前` / `名前タグ`）— タグ名は完全一致の文字列比較のみで、正規表現やコマンド
 *    として解釈しない（プロンプトインジェクション耐性は構造的に担保）。
 * 2. 追加時期（去年・今月など + 買った/追加した等）
 * 3. 場所（「◯◯にある」）
 * 4. 読書状態キーワード（未読・読みかけ・読了・中断）
 * 5. NDC類名（自然科学・文学など）と「N類」
 *
 * 裸のキーワード（例:「未読」）は、同名タグが存在しても組み込みキーワードとして解釈する。
 * タグとして検索したい場合は `#未読` または「未読タグ」と明示する。
 */
object NaturalLanguageQueryParser {
    fun parse(
        query: String,
        tags: List<Tag> = emptyList(),
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): NaturalLanguageInterpretation {
        val source = query.trim()
        if (source.isEmpty()) return NaturalLanguageInterpretation.NONE
        val consumed = BooleanArray(source.length)
        val tokens = mutableListOf<InterpretedToken>()

        extractExplicitTags(source, consumed, tags, tokens)
        extractAddedRange(source, consumed, nowMillis, timeZone, tokens)
        extractLocation(source, consumed, tokens)
        extractReadingStatus(source, consumed, tokens)
        extractNdcCategory(source, consumed, tokens)

        if (tokens.isEmpty()) return NaturalLanguageInterpretation.NONE
        return NaturalLanguageInterpretation(
            source = source,
            tokens = tokens.sortedBy { token -> token.range.first },
        )
    }

    private fun extractExplicitTags(
        source: String,
        consumed: BooleanArray,
        tags: List<Tag>,
        tokens: MutableList<InterpretedToken>,
    ) {
        // 長い名前を先に照合し、名前が包含関係にあるタグの取り違えを防ぐ。
        val candidates = tags.sortedByDescending { tag -> tag.name.length }
        for (tag in candidates) {
            if (tokens.count { it.chip is SearchInterpretationChip.TagRef } >= TagNameRules.MAX_TAG_FILTERS) return
            if (tag.name.isEmpty()) continue
            val patterns = listOf("#${tag.name}", "${tag.name}タグ")
            for (pattern in patterns) {
                val index = indexOfUnconsumed(source, consumed, pattern)
                if (index < 0) continue
                val range = index until index + pattern.length
                markConsumed(consumed, range)
                tokens += InterpretedToken(SearchInterpretationChip.TagRef(tag.id, tag.name), range.toClosed())
                break
            }
        }
    }

    private fun extractAddedRange(
        source: String,
        consumed: BooleanArray,
        nowMillis: Long,
        timeZone: TimeZone,
        tokens: MutableList<InterpretedToken>,
    ) {
        val match =
            ADDED_KEYWORDS
                .mapNotNull { keyword ->
                    val index = indexOfUnconsumed(source, consumed, keyword)
                    if (index < 0) null else index to keyword
                }.minWithOrNull(compareBy({ it.first }, { -it.second.length }))
                ?: return
        val (index, keyword) = match
        var end = index + keyword.length
        if (source.startsWith("に", end) && !isConsumed(consumed, end, end + 1)) end += 1
        for (verb in ADDED_VERBS) {
            if (source.startsWith(verb, end) && !isConsumed(consumed, end, end + verb.length)) {
                end += verb.length
                break
            }
        }
        val (afterMillis, beforeMillis) = addedRangeFor(keyword, nowMillis, timeZone) ?: return
        val range = index until end
        markConsumed(consumed, range)
        tokens +=
            InterpretedToken(
                SearchInterpretationChip.Added(
                    rangeLabel = keyword,
                    afterMillis = afterMillis,
                    beforeMillis = beforeMillis,
                ),
                range.toClosed(),
            )
    }

    private fun extractLocation(
        source: String,
        consumed: BooleanArray,
        tokens: MutableList<InterpretedToken>,
    ) {
        val match =
            LOCATION_PATTERN
                .findAll(source)
                .firstOrNull { candidate ->
                    !isConsumed(consumed, candidate.range.first, candidate.range.last + 1)
                } ?: return
        val locationQuery = requireNotNull(match.groups[1]).value
        markConsumed(consumed, match.range.first..match.range.last)
        tokens +=
            InterpretedToken(
                SearchInterpretationChip.Location(locationQuery),
                match.range,
            )
    }

    private fun extractReadingStatus(
        source: String,
        consumed: BooleanArray,
        tokens: MutableList<InterpretedToken>,
    ) {
        val match =
            STATUS_KEYWORDS
                .mapNotNull { (keyword, status) ->
                    val index = indexOfUnconsumed(source, consumed, keyword)
                    if (index < 0) null else Triple(index, keyword, status)
                }.minWithOrNull(compareBy({ it.first }, { -it.second.length }))
                ?: return
        val (index, keyword, status) = match
        val range = index until index + keyword.length
        markConsumed(consumed, range)
        tokens += InterpretedToken(SearchInterpretationChip.Status(status), range.toClosed())
    }

    private fun extractNdcCategory(
        source: String,
        consumed: BooleanArray,
        tokens: MutableList<InterpretedToken>,
    ) {
        val labelMatch =
            NdcCategory.all
                .mapNotNull { category ->
                    val index = indexOfUnconsumed(source, consumed, category.label)
                    if (index < 0) null else Triple(index, category.label, category)
                }.minWithOrNull(compareBy({ it.first }, { -it.second.length }))
        val digitMatch =
            NDC_DIGIT_PATTERN
                .findAll(source)
                .firstOrNull { candidate ->
                    !isConsumed(consumed, candidate.range.first, candidate.range.last + 1)
                }?.let { candidate ->
                    val digit = requireNotNull(candidate.groups[1]).value.toHalfWidthDigit()
                    NdcCategory.all.getOrNull(digit)?.let { category ->
                        Triple(candidate.range.first, candidate.value, category)
                    }
                }
        val match =
            listOfNotNull(labelMatch, digitMatch)
                .minByOrNull { it.first }
                ?: return
        val (index, matchedText, category) = match
        val range = index until index + matchedText.length
        markConsumed(consumed, range)
        tokens += InterpretedToken(SearchInterpretationChip.Ndc(category), range.toClosed())
    }

    private fun addedRangeFor(
        keyword: String,
        nowMillis: Long,
        timeZone: TimeZone,
    ): Pair<Long, Long>? {
        val calendar =
            Calendar.getInstance(timeZone).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        return when (keyword) {
            "今日" -> {
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                start to calendar.timeInMillis
            }

            "今月" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val start = calendar.timeInMillis
                calendar.add(Calendar.MONTH, 1)
                start to calendar.timeInMillis
            }

            "先月" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val end = calendar.timeInMillis
                calendar.add(Calendar.MONTH, -1)
                calendar.timeInMillis to end
            }

            "今年" -> {
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val start = calendar.timeInMillis
                calendar.add(Calendar.YEAR, 1)
                start to calendar.timeInMillis
            }

            "去年", "昨年" -> {
                calendar.set(Calendar.MONTH, Calendar.JANUARY)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val end = calendar.timeInMillis
                calendar.add(Calendar.YEAR, -1)
                calendar.timeInMillis to end
            }

            else -> {
                null
            }
        }
    }

    private fun indexOfUnconsumed(
        source: String,
        consumed: BooleanArray,
        literal: String,
    ): Int {
        var index = source.indexOf(literal)
        while (index >= 0) {
            if (!isConsumed(consumed, index, index + literal.length)) return index
            index = source.indexOf(literal, index + 1)
        }
        return -1
    }

    private fun isConsumed(
        consumed: BooleanArray,
        startInclusive: Int,
        endExclusive: Int,
    ): Boolean = (startInclusive until endExclusive).any { index -> consumed[index] }

    private fun markConsumed(
        consumed: BooleanArray,
        range: IntRange,
    ) {
        range.forEach { index -> consumed[index] = true }
    }

    private fun IntRange.toClosed(): IntRange = this

    private fun String.toHalfWidthDigit(): Int {
        val char = single()
        return if (char in '０'..'９') char - '０' else char.digitToInt()
    }

    private val LOCATION_PATTERN =
        Regex("([^\\s、。の]+)(?:に置いてある|にしまってある|に置いた|にある)")

    private val NDC_DIGIT_PATTERN = Regex("([0-9０-９])類")

    private val ADDED_KEYWORDS = listOf("今日", "今月", "先月", "今年", "去年", "昨年")

    private val ADDED_VERBS =
        listOf("購入した", "追加した", "登録した", "買った", "追加", "購入", "登録")

    private val STATUS_KEYWORDS =
        listOf(
            "読み終わった" to ReadingStatus.READ,
            "読み終えた" to ReadingStatus.READ,
            "読了済み" to ReadingStatus.READ,
            "読了した" to ReadingStatus.READ,
            "読了" to ReadingStatus.READ,
            "読んだ" to ReadingStatus.READ,
            "読みかけ" to ReadingStatus.READING,
            "読み掛け" to ReadingStatus.READING,
            "読んでいる" to ReadingStatus.READING,
            "読んでる" to ReadingStatus.READING,
            "読書中" to ReadingStatus.READING,
            "中断した" to ReadingStatus.PAUSED,
            "中断中" to ReadingStatus.PAUSED,
            "中断" to ReadingStatus.PAUSED,
            "積ん読" to ReadingStatus.UNREAD,
            "積読" to ReadingStatus.UNREAD,
            "未読" to ReadingStatus.UNREAD,
        )
}

/** 消費後に残った検索語から、区切りの助詞と一般名詞ノイズを取り除く。 */
private fun cleanUpResidual(remaining: String): String =
    remaining
        .split(SEGMENT_SEPARATOR)
        .map { segment -> segment.trim(*PARTICLE_CHARS) }
        .filter { segment -> segment.isNotEmpty() && segment !in NOISE_WORDS }
        .joinToString(" ")

private val SEGMENT_SEPARATOR = Regex("[\\s、。]+")

private val PARTICLE_CHARS = charArrayOf('の', 'が', 'を', 'は', 'で', 'と', 'に', 'へ', 'や', 'か')

private val NOISE_WORDS = setOf("本", "書籍", "蔵書", "冊", "やつ", "もの")
