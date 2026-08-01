package dev.ndcshelf.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.ui.navigation.TopLevelDestination
import dev.ndcshelf.app.ui.text.labelRes
import dev.ndcshelf.app.ui.theme.NdcShelfTheme

/** compact時の下部NavigationBar。テストとスクリーンショットの識別に使う。 */
const val ADAPTIVE_NAVIGATION_BAR_TEST_TAG = "adaptive-navigation-bar"

/** medium/expanded時の左NavigationRail。 */
const val ADAPTIVE_NAVIGATION_RAIL_TEST_TAG = "adaptive-navigation-rail"

/**
 * ウィンドウ幅に応じてナビゲーションの配置と本文の最大幅を切り替える唯一のScaffold。
 *
 * - compact: 下部`NavigationBar`。本文は幅いっぱい。
 * - medium / expanded: 左`NavigationRail`。本文は`AdaptiveLayout.contentMaxWidth`で中央寄せ。
 *
 * TalkBackの読み上げ順は「ナビゲーション → 本文」を維持するため、railは`Row`の先頭へ置く。
 * compactでも`NavigationBar`はScaffoldのbottomBarとして本文の後に読まれる（従来と同じ）。
 *
 * @param layoutOverride テスト・Previewでサイズクラスを固定したい場合のみ指定する。
 *   通常は`null`で、実際のウィンドウ実寸から判定する。
 */
@Composable
fun AdaptiveNavigationScaffold(
    isSelected: (TopLevelDestination) -> Boolean,
    onSelectDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    layoutOverride: AdaptiveLayout? = null,
    content: @Composable (layout: AdaptiveLayout, contentPadding: PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = layoutOverride ?: AdaptiveLayout.of(maxWidth, maxHeight)
        Scaffold(
            snackbarHost = snackbarHost,
            bottomBar = {
                if (!layout.usesNavigationRail) {
                    NavigationBar(
                        modifier = Modifier.testTag(ADAPTIVE_NAVIGATION_BAR_TEST_TAG),
                        tonalElevation = 2.dp,
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            NavigationBarItem(
                                selected = isSelected(destination),
                                onClick = { onSelectDestination(destination) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { scaffoldPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (layout.usesNavigationRail) {
                    NavigationRail(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .testTag(ADAPTIVE_NAVIGATION_RAIL_TEST_TAG),
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            NavigationRailItem(
                                selected = isSelected(destination),
                                onClick = { onSelectDestination(destination) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = layout.contentHorizontalPadding),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = layout.contentMaxWidth)
                                .fillMaxHeight(),
                    ) {
                        content(layout, scaffoldPadding)
                    }
                }
            }
        }
    }
}

@Preview(name = "compact 411dp", widthDp = 411, heightDp = 891)
@Composable
private fun AdaptiveScaffoldCompactPreview() = AdaptiveScaffoldPreview()

@Preview(name = "medium 840dp", widthDp = 700, heightDp = 900)
@Composable
private fun AdaptiveScaffoldMediumPreview() = AdaptiveScaffoldPreview()

@Preview(name = "expanded 1280dp", widthDp = 1280, heightDp = 800)
@Composable
private fun AdaptiveScaffoldExpandedPreview() = AdaptiveScaffoldPreview()

@Composable
private fun AdaptiveScaffoldPreview() {
    NdcShelfTheme {
        AdaptiveNavigationScaffold(
            isSelected = { it == TopLevelDestination.LIBRARY },
            onSelectDestination = {},
        ) { layout, _ ->
            Box(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = layout.widthClass.name,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
