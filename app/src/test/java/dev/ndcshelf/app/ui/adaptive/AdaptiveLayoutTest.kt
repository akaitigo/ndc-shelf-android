package dev.ndcshelf.app.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * サイズクラスの境界値と、そこから導かれるレイアウト方針の回帰テスト。
 * 境界は docs/ADAPTIVE_LAYOUT.md の表と一致させる（600dp / 840dp）。
 */
class AdaptiveLayoutTest {
    @Test
    fun widthBelow600dpIsCompact() {
        assertEquals(WindowWidthClass.COMPACT, layoutOf(320.dp).widthClass)
        assertEquals(WindowWidthClass.COMPACT, layoutOf(411.dp).widthClass)
        assertEquals(WindowWidthClass.COMPACT, layoutOf(599.dp).widthClass)
    }

    @Test
    fun widthFrom600dpIsMedium() {
        assertEquals(WindowWidthClass.MEDIUM, layoutOf(600.dp).widthClass)
        assertEquals(WindowWidthClass.MEDIUM, layoutOf(720.dp).widthClass)
        assertEquals(WindowWidthClass.MEDIUM, layoutOf(839.dp).widthClass)
    }

    @Test
    fun widthFrom840dpIsExpanded() {
        assertEquals(WindowWidthClass.EXPANDED, layoutOf(840.dp).widthClass)
        assertEquals(WindowWidthClass.EXPANDED, layoutOf(1280.dp).widthClass)
    }

    @Test
    fun negativeOrZeroWidthFallsBackToCompact() {
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayout.of(0.dp, 0.dp).widthClass)
        assertEquals(WindowWidthClass.COMPACT, AdaptiveLayout.of((-100).dp, (-100).dp).widthClass)
    }

    @Test
    fun compactUsesBottomBarAndSinglePaneWithoutWidthLimit() {
        val layout = AdaptiveLayout.Compact
        assertFalse(layout.usesNavigationRail)
        assertFalse(layout.usesListDetailPanes)
        assertEquals(Dp.Infinity, layout.contentMaxWidth)
    }

    @Test
    fun mediumUsesRailButKeepsSinglePane() {
        val layout = AdaptiveLayout.Medium
        assertTrue(layout.usesNavigationRail)
        assertFalse(layout.usesListDetailPanes)
        assertEquals(AdaptiveLayout.MEDIUM_CONTENT_MAX_WIDTH, layout.contentMaxWidth)
    }

    @Test
    fun expandedUsesRailAndListDetailPanes() {
        val layout = AdaptiveLayout.Expanded
        assertTrue(layout.usesNavigationRail)
        assertTrue(layout.usesListDetailPanes)
        assertEquals(AdaptiveLayout.EXPANDED_CONTENT_MAX_WIDTH, layout.contentMaxWidth)
    }

    @Test
    fun expandedDetailPaneStaysWithinReadableWidth() {
        // 一覧ペインを引いた詳細ペイン幅が、単一ペインの上限（720dp）を超えないこと。
        val detailWidth = AdaptiveLayout.EXPANDED_CONTENT_MAX_WIDTH - AdaptiveLayout.LIST_PANE_WIDTH
        assertTrue(detailWidth <= AdaptiveLayout.MEDIUM_CONTENT_MAX_WIDTH)
    }

    private fun layoutOf(width: Dp): AdaptiveLayout = AdaptiveLayout.of(width, 900.dp)
}
