package dev.ndcshelf.app.ui.adaptive

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * ウィンドウ幅のサイズクラス。端末種別（タブレット・折りたたみ）ではなく
 * **アプリへ与えられたウィンドウ幅**で分岐する（docs/ADAPTIVE_LAYOUT.md）。
 * 分割画面・自由ウィンドウ・折りたたみの姿勢変更はすべて幅の変化として現れる。
 */
enum class WindowWidthClass {
    /** 幅 < 600dp。ハンドセット縦持ち、分割画面の狭い側。 */
    COMPACT,

    /** 600dp <= 幅 < 840dp。ハンドセット横持ち、小型タブレット、折りたたみ内側。 */
    MEDIUM,

    /** 840dp <= 幅。タブレット横持ち、デスクトップ級ウィンドウ。 */
    EXPANDED,
}

/**
 * サイズクラスから導いたレイアウト方針。**この型の生成は`NdcShelfApp`直下の
 * `AdaptiveNavigationScaffold`一箇所だけ**で行い、各画面へは真偽値・寸法として渡す。
 * 画面側にサイズクラスの分岐を書かないことで、端末種別ごとの分岐増殖を防ぐ。
 */
@Immutable
data class AdaptiveLayout(
    val widthClass: WindowWidthClass,
) {
    /** medium以上は左側のNavigationRail、compactは下部のNavigationBar。 */
    val usesNavigationRail: Boolean
        get() = widthClass != WindowWidthClass.COMPACT

    /** expandedだけ一覧＋詳細の2ペインを同時に表示する。 */
    val usesListDetailPanes: Boolean
        get() = widthClass == WindowWidthClass.EXPANDED

    /** 本文が横へ伸びすぎないための上限。compactは端末幅いっぱい。 */
    val contentMaxWidth: Dp
        get() =
            when (widthClass) {
                WindowWidthClass.COMPACT -> Dp.Infinity
                WindowWidthClass.MEDIUM -> MEDIUM_CONTENT_MAX_WIDTH
                WindowWidthClass.EXPANDED -> EXPANDED_CONTENT_MAX_WIDTH
            }

    /** 2ペイン時の一覧ペイン幅。詳細ペインは残り幅（最大720dp）を占める。 */
    val listPaneWidth: Dp
        get() = LIST_PANE_WIDTH

    /** ペイン外周の水平余白。大画面ほど広げて視線移動距離を抑える。 */
    val contentHorizontalPadding: Dp
        get() =
            when (widthClass) {
                WindowWidthClass.COMPACT -> 0.dp
                WindowWidthClass.MEDIUM -> 8.dp
                WindowWidthClass.EXPANDED -> 12.dp
            }

    companion object {
        val Compact = AdaptiveLayout(WindowWidthClass.COMPACT)
        val Medium = AdaptiveLayout(WindowWidthClass.MEDIUM)
        val Expanded = AdaptiveLayout(WindowWidthClass.EXPANDED)

        /** medium幅の本文上限。1行あたりの文字数が読みやすい範囲に収まる値。 */
        val MEDIUM_CONTENT_MAX_WIDTH = 720.dp

        /** expanded幅の本文上限。一覧360dp + 詳細720dpを収める。 */
        val EXPANDED_CONTENT_MAX_WIDTH = 1080.dp

        /** 2ペイン時の一覧ペイン固定幅。 */
        val LIST_PANE_WIDTH = 360.dp

        /**
         * ウィンドウの実寸からレイアウト方針を決める。
         * breakpointは`androidx.window`のWindowSizeClass（Material 3準拠の600dp/840dp）に従う。
         */
        fun of(
            widthDp: Dp,
            heightDp: Dp,
        ): AdaptiveLayout = of(widthDp.value, heightDp.value)

        fun of(
            widthDp: Float,
            heightDp: Float,
        ): AdaptiveLayout {
            val sizeClass =
                WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(
                    widthDp = widthDp.coerceAtLeast(0f),
                    heightDp = heightDp.coerceAtLeast(0f),
                )
            val widthClass =
                when {
                    sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> {
                        WindowWidthClass.EXPANDED
                    }

                    sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> {
                        WindowWidthClass.MEDIUM
                    }

                    else -> {
                        WindowWidthClass.COMPACT
                    }
                }
            return AdaptiveLayout(widthClass)
        }
    }
}
