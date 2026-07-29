package dev.ndcshelf.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNameRulesTest {
    @Test
    fun `names are trimmed and inner whitespace runs collapse to one space`() {
        val valid = TagNameRules.validate("  SF   ハード \t SF  ") as TagNameValidation.Valid
        assertEquals("SF ハード SF", valid.normalized)
    }

    @Test
    fun `empty and blank names are rejected`() {
        assertTrue(TagNameRules.validate("") is TagNameValidation.Invalid)
        assertTrue(TagNameRules.validate("   ") is TagNameValidation.Invalid)
    }

    @Test
    fun `length limit is enforced after normalization`() {
        val boundary = "あ".repeat(TagNameRules.MAX_NAME_LENGTH)
        assertTrue(TagNameRules.validate(boundary) is TagNameValidation.Valid)
        assertTrue(
            TagNameRules.validate(boundary + "あ") is TagNameValidation.Invalid,
        )
        // 前後空白は正規化で消えるため上限判定に影響しない。
        assertTrue(TagNameRules.validate("  $boundary  ") is TagNameValidation.Valid)
    }

    @Test
    fun `control characters are rejected`() {
        listOf("タグ\u0000", "タグ\u0007", "\u001Bタグ").forEach { raw ->
            assertTrue("should reject $raw", TagNameRules.validate(raw) is TagNameValidation.Invalid)
        }
        // 改行・タブは空白として1つのスペースへ正規化され、拒否しない。
        val newline = TagNameRules.validate("SF\nハード") as TagNameValidation.Valid
        assertEquals("SF ハード", newline.normalized)
    }
}
