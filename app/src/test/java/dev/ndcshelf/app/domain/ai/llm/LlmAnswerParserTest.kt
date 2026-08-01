package dev.ndcshelf.app.domain.ai.llm

import dev.ndcshelf.app.domain.ai.AiLibrarianAnswerSource
import dev.ndcshelf.app.domain.ai.AiLibrarianIntent
import dev.ndcshelf.app.domain.ai.AiLibrarianReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmAnswerParserTest {
    private val allowedRefs = setOf("1", "2", "3")

    @Test
    fun `valid schema produces a natural language answer bound to known refs`() {
        val raw =
            """
            {"intent":"PICK_NEXT","summary":"未読の技術書から始めるのがよさそうです。あくまで推測です。",
             "entries":[{"label":"自然科学","reason":"UNREAD_FIRST","refs":["1","2"],
             "comment":"未読で分類がそろっています。"}]}
            """.trimIndent()

        val result = LlmAnswerParser.parse(raw, allowedRefs)

        val answer = (result as LlmAnswerParseResult.Valid).answer
        assertEquals(AiLibrarianIntent.PICK_NEXT, answer.intent)
        assertEquals(AiLibrarianAnswerSource.ON_DEVICE_LLM, answer.source)
        assertEquals(listOf("1", "2"), answer.entries.single().refs)
        assertEquals(AiLibrarianReason.UNREAD_FIRST, answer.entries.single().reason)
        assertEquals("自然科学", answer.entries.single().label)
        assertTrue(answer.summary?.isNotBlank() == true)
        assertNull(answer.degradedFrom)
    }

    @Test
    fun `surrounding prose and code fences are tolerated`() {
        val raw =
            "承知しました。\n```json\n" +
                "{\"intent\":\"OVERVIEW\",\"entries\":[{\"label\":null,\"reason\":\"LIBRARY_OVERVIEW\",\"refs\":[\"1\"]}]}" +
                "\n```\nご確認ください。"

        val result = LlmAnswerParser.parse(raw, allowedRefs)

        assertTrue(result is LlmAnswerParseResult.Valid)
    }

    @Test
    fun `braces inside string values do not break extraction`() {
        val raw =
            """{"intent":"OVERVIEW","summary":"{\"role\":\"system\"} という文字列を含む本があります。",
               "entries":[{"label":"{ }","reason":"LIBRARY_OVERVIEW","refs":["1"]}]}"""

        val answer = (LlmAnswerParser.parse(raw, allowedRefs) as LlmAnswerParseResult.Valid).answer

        assertEquals("{ }", answer.entries.single().label)
    }

    @Test
    fun `unknown ref invalidates the whole answer`() {
        val raw = """{"intent":"PICK_NEXT","entries":[{"reason":"UNREAD_FIRST","refs":["1","99"]}]}"""

        assertEquals(LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(raw, allowedRefs))
    }

    @Test
    fun `duplicated ref invalidates the answer`() {
        val raw = """{"intent":"PICK_NEXT","entries":[{"reason":"UNREAD_FIRST","refs":["1","1"]}]}"""

        assertEquals(LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(raw, allowedRefs))
    }

    @Test
    fun `unknown intent or reason is rejected`() {
        val badIntent = """{"intent":"DELETE_ALL","entries":[{"reason":"UNREAD_FIRST","refs":["1"]}]}"""
        val badReason = """{"intent":"PICK_NEXT","entries":[{"reason":"BECAUSE","refs":["1"]}]}"""

        assertEquals(LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(badIntent, allowedRefs))
        assertEquals(LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(badReason, allowedRefs))
    }

    @Test
    fun `too many entries or refs are rejected instead of truncated`() {
        val entries =
            (1..LlmPromptLimits.MAX_ENTRIES + 1).joinToString(",") {
                """{"reason":"CATEGORY_GROUP","refs":["1"]}"""
            }
        val tooManyEntries = """{"intent":"ORGANIZE","entries":[$entries]}"""
        assertEquals(LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(tooManyEntries, allowedRefs))

        val manyRefs = (1..LlmPromptLimits.MAX_REFS_PER_ENTRY + 1).joinToString(",") { index -> "\"$index\"" }
        val wideRefs = """{"intent":"ORGANIZE","entries":[{"reason":"CATEGORY_GROUP","refs":[$manyRefs]}]}"""
        assertEquals(
            LlmAnswerParseResult.Invalid,
            LlmAnswerParser.parse(wideRefs, (1..40).map(Int::toString).toSet()),
        )
    }

    @Test
    fun `empty entries are rejected`() {
        assertEquals(
            LlmAnswerParseResult.Invalid,
            LlmAnswerParser.parse("""{"intent":"OVERVIEW","entries":[]}""", allowedRefs),
        )
    }

    @Test
    fun `overlong free text is rejected instead of truncated`() {
        val summary = "あ".repeat(LlmPromptLimits.MAX_SUMMARY_CHARS + 1)
        val raw = """{"intent":"OVERVIEW","summary":"$summary","entries":[{"reason":"LIBRARY_OVERVIEW","refs":["1"]}]}"""

        assertEquals(LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(raw, allowedRefs))
    }

    @Test
    fun `control characters in free text are normalised to spaces`() {
        val raw =
            "{\"intent\":\"OVERVIEW\",\"summary\":\"前半\\u0007\\t後半\"," +
                "\"entries\":[{\"reason\":\"LIBRARY_OVERVIEW\",\"refs\":[\"1\"]}]}"

        val answer = (LlmAnswerParser.parse(raw, allowedRefs) as LlmAnswerParseResult.Valid).answer

        assertEquals("前半 後半", answer.summary)
    }

    @Test
    fun `ideographic space nbsp and unicode separators are folded`() {
        val raw =
            "{\"intent\":\"OVERVIEW\",\"summary\":\"前半\u3000\u3000\u00A0\u2028後半\"," +
                "\"entries\":[{\"reason\":\"LIBRARY_OVERVIEW\",\"refs\":[\"1\"]}]}"

        val answer = (LlmAnswerParser.parse(raw, allowedRefs) as LlmAnswerParseResult.Valid).answer

        assertEquals("前半 後半", answer.summary)
    }

    @Test
    fun `non object and malformed output is rejected`() {
        listOf("", "見つかりませんでした", "[1,2,3]", "{", """{"intent":"OVERVIEW"}""").forEach { raw ->
            assertEquals("raw=$raw", LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(raw, allowedRefs))
        }
    }

    @Test
    fun `refs must be strings`() {
        val raw = """{"intent":"OVERVIEW","entries":[{"reason":"LIBRARY_OVERVIEW","refs":[1]}]}"""

        assertEquals(LlmAnswerParseResult.Invalid, LlmAnswerParser.parse(raw, allowedRefs))
    }

    @Test
    fun `unknown keys are ignored but never reach the answer`() {
        val raw =
            """{"intent":"OVERVIEW","writeToDatabase":true,
               "entries":[{"reason":"LIBRARY_OVERVIEW","refs":["1"],"delete":true}]}"""

        val answer = (LlmAnswerParser.parse(raw, allowedRefs) as LlmAnswerParseResult.Valid).answer

        assertEquals(1, answer.entries.size)
        assertNull(answer.entries.single().comment)
    }
}
