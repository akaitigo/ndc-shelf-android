package dev.ndcshelf.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 2ペイン表示で詳細側が未選択のときのプレースホルダ。テスト識別用タグ。 */
const val EMPTY_DETAIL_PANE_TEST_TAG = "empty-detail-pane"

/**
 * 2ペイン表示で詳細側がまだ選択されていないときのプレースホルダ。
 * 空白のままにせず、次の操作（一覧から選ぶ）を文言で明示する。
 */
@Composable
fun EmptyDetailPane(
    message: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(
        modifier = modifier.testTag(EMPTY_DETAIL_PANE_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            modifier =
                Modifier.padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = contentPadding.calculateTopPadding() + 24.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
