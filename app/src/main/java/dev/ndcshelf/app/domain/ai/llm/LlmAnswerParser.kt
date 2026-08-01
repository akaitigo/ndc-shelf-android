package dev.ndcshelf.app.domain.ai.llm

import dev.ndcshelf.app.domain.ai.AiLibrarianAnswer
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswerEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswerSource
import dev.ndcshelf.app.domain.ai.AiLibrarianIntent
import dev.ndcshelf.app.domain.ai.AiLibrarianReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LLM出力の厳格な検証。schemaを1点でも満たさなければ破棄し、部分的な回答を
 * 表示しない（呼び出し側は`INVALID_RESPONSE`として扱う）。
 *
 * - refsは要求に含まれるrefだけを許可し、未知refが1つでもあれば全体を破棄する
 * - 件数上限・文字数上限を超えたら破棄する（切り詰めて通さない）
 * - intent・reasonは既知enumのみ許可する
 * - 自由文は制御文字を空白へ置換し、markup記号を持ち込んでも素のテキストとして表示する
 */
object LlmAnswerParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        raw: String,
        allowedRefs: Set<String>,
    ): LlmAnswerParseResult {
        val objectText = extractFirstJsonObject(raw) ?: return LlmAnswerParseResult.Invalid
        val root =
            runCatching { json.parseToJsonElement(objectText) as? JsonObject }
                .getOrNull() ?: return LlmAnswerParseResult.Invalid

        val intent = root.stringOrNull("intent")?.let(::intentOrNull) ?: return LlmAnswerParseResult.Invalid
        val entriesJson = root["entries"] as? JsonArray ?: return LlmAnswerParseResult.Invalid
        if (entriesJson.isEmpty() || entriesJson.size > LlmPromptLimits.MAX_ENTRIES) {
            return LlmAnswerParseResult.Invalid
        }

        val entries = mutableListOf<AiLibrarianAnswerEntry>()
        for (element in entriesJson) {
            val entryJson = element as? JsonObject ?: return LlmAnswerParseResult.Invalid
            val reason =
                entryJson.stringOrNull("reason")?.let(::reasonOrNull) ?: return LlmAnswerParseResult.Invalid
            val refsJson = entryJson["refs"] as? JsonArray ?: return LlmAnswerParseResult.Invalid
            if (refsJson.isEmpty() || refsJson.size > LlmPromptLimits.MAX_REFS_PER_ENTRY) {
                return LlmAnswerParseResult.Invalid
            }
            val refs = mutableListOf<String>()
            for (refElement in refsJson) {
                val ref =
                    (refElement as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        ?: return LlmAnswerParseResult.Invalid
                if (ref !in allowedRefs || ref in refs) return LlmAnswerParseResult.Invalid
                refs += ref
            }
            val label =
                entryJson.stringOrNull("label")?.let { value ->
                    sanitizeOrNull(value, LlmPromptLimits.MAX_LABEL_CHARS) ?: return LlmAnswerParseResult.Invalid
                }
            val comment =
                entryJson.stringOrNull("comment")?.let { value ->
                    sanitizeOrNull(value, LlmPromptLimits.MAX_COMMENT_CHARS) ?: return LlmAnswerParseResult.Invalid
                }
            entries += AiLibrarianAnswerEntry(label = label, reason = reason, refs = refs, comment = comment)
        }

        val summary =
            root.stringOrNull("summary")?.let { value ->
                sanitizeOrNull(value, LlmPromptLimits.MAX_SUMMARY_CHARS) ?: return LlmAnswerParseResult.Invalid
            }

        return LlmAnswerParseResult.Valid(
            AiLibrarianAnswer(
                intent = intent,
                entries = entries.toList(),
                summary = summary,
                source = AiLibrarianAnswerSource.ON_DEVICE_LLM,
            ),
        )
    }

    /**
     * 最初の対応の取れたJSONオブジェクトを取り出す。文字列リテラル内の括弧と
     * escapeを解釈するため、書名に`{`や`"`を含んでいても境界を誤らない。
     */
    internal fun extractFirstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            when {
                escaped -> {
                    escaped = false
                }

                inString && char == '\\' -> {
                    escaped = true
                }

                char == '"' -> {
                    inString = !inString
                }

                inString -> {
                    Unit
                }

                char == '{' -> {
                    depth += 1
                }

                char == '}' -> {
                    depth -= 1
                    if (depth == 0) return raw.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
    }

    private fun intentOrNull(value: String): AiLibrarianIntent? = AiLibrarianIntent.entries.firstOrNull { intent -> intent.name == value }

    private fun reasonOrNull(value: String): AiLibrarianReason? = AiLibrarianReason.entries.firstOrNull { reason -> reason.name == value }

    /** 制御文字を空白へ置換し、連続空白を畳む。上限超過・空文字はnullを返す。 */
    private fun sanitizeOrNull(
        value: String,
        maxLength: Int,
    ): String? {
        val withoutControls =
            buildString(value.length) {
                value.forEach { char -> append(if (char.isISOControl()) ' ' else char) }
            }
        val cleaned = withoutControls.replace(WHITESPACE_RUN, " ").trim()
        return when {
            cleaned.isEmpty() -> null
            cleaned.length > maxLength -> null
            else -> cleaned
        }
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}

sealed interface LlmAnswerParseResult {
    data class Valid(
        val answer: AiLibrarianAnswer,
    ) : LlmAnswerParseResult

    data object Invalid : LlmAnswerParseResult
}
