package dev.ndcshelf.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存値がロケールに依存しないことの回帰テスト。
 *
 * v0.6.0では未設定の置き場所を`"未設定"`、所蔵ラベルの既定を`"所蔵本"`として
 * データベースへ書き込み、画面はその保存値をそのまま表示していた。英語ロケールの
 * 実機で日本語が残ることを確認したため、保存値を空文字へ統一した。
 */
class LibraryDefaultsTest {
    @Test
    fun `unset values are locale independent`() {
        assertEquals("", LibraryDefaults.UNSET_LOCATION)
        assertEquals("", LibraryDefaults.UNSET_COPY_LABEL)
    }

    @Test
    fun `legacy japanese sentinel is normalized`() {
        assertEquals(LibraryDefaults.UNSET_LOCATION, LibraryDefaults.normalizeLocation("未設定"))
        assertEquals(LibraryDefaults.UNSET_COPY_LABEL, LibraryDefaults.normalizeCopyLabel("所蔵本"))
    }

    @Test
    fun `legacy english sentinel written by localized ui is normalized`() {
        // 英語ロケールの端末では localize された "Not set" が保存されていた。
        assertEquals(LibraryDefaults.UNSET_LOCATION, LibraryDefaults.normalizeLocation("Not set"))
    }

    @Test
    fun `user entered values are preserved`() {
        assertEquals("書斎 / 棚A / 上段", LibraryDefaults.normalizeLocation("書斎 / 棚A / 上段"))
        assertEquals("保存用", LibraryDefaults.normalizeCopyLabel("保存用"))
    }

    @Test
    fun `blank and null inputs collapse to unset`() {
        assertEquals(LibraryDefaults.UNSET_LOCATION, LibraryDefaults.normalizeLocation(null))
        assertEquals(LibraryDefaults.UNSET_LOCATION, LibraryDefaults.normalizeLocation("   "))
        assertEquals(LibraryDefaults.UNSET_COPY_LABEL, LibraryDefaults.normalizeCopyLabel(null))
        assertEquals(LibraryDefaults.UNSET_COPY_LABEL, LibraryDefaults.normalizeCopyLabel("  "))
    }

    @Test
    fun `sentinels never contain natural language`() {
        // 保存値へ自然言語が混ざると、その言語の利用者以外へ露出する。
        listOf(LibraryDefaults.UNSET_LOCATION, LibraryDefaults.UNSET_COPY_LABEL).forEach { value ->
            assertTrue(
                "Stored sentinel must not carry displayable text: '$value'",
                value.isEmpty(),
            )
        }
    }
}
