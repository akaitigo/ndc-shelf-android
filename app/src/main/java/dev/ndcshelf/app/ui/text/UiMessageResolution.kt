package dev.ndcshelf.app.ui.text

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.ndcshelf.app.domain.text.UiMessage

/**
 * Compose上で[UiMessage]を現在のロケールへ解決する。
 *
 * `LocalResources`は端末の言語設定が変わったときに再コンポーズを起こす
 * （`androidx.compose.ui.res.stringResource`と同じ仕組み）。
 */
@Composable
@ReadOnlyComposable
fun UiMessage.resolve(): String = resolve(localResources())

@Composable
@ReadOnlyComposable
fun localResources(): Resources = LocalResources.current
