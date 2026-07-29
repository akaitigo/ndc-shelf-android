package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.InsightsUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.insights.FinishedTrendInsight
import dev.ndcshelf.app.domain.insights.FinishedTrendPoint
import dev.ndcshelf.app.domain.insights.NdcShare
import dev.ndcshelf.app.domain.insights.RediscoveryCandidate
import dev.ndcshelf.app.domain.insights.RediscoveryReason
import dev.ndcshelf.app.domain.insights.TsundokuCandidate
import dev.ndcshelf.app.domain.model.LibraryBook
import kotlin.math.roundToInt

/**
 * 分析（Insights）画面。端末内の蔵書・読書履歴だけから傾向と再発見候補を提示する。
 * 指標の定義と表現ガイドラインは docs/INSIGHTS.md を正本とする。
 */
@Composable
fun InsightsScreen(
    state: InsightsUiState,
    onExcludeBook: (String) -> Unit,
    onResetExclusions: () -> Unit,
    contentPadding: PaddingValues,
) {
    when (state) {
        InsightsUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is InsightsUiState.Ready -> {
            InsightsContent(
                state = state,
                onExcludeBook = onExcludeBook,
                onResetExclusions = onResetExclusions,
                contentPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun InsightsContent(
    state: InsightsUiState.Ready,
    onExcludeBook: (String) -> Unit,
    onResetExclusions: () -> Unit,
    contentPadding: PaddingValues,
) {
    val insights = state.insights
    val classifiedBooks = remember(state.books) { state.books.filter { it.ndcCode != null } }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.insights_reset_dialog_title)) },
            text = { Text(stringResource(R.string.insights_reset_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetExclusions()
                        showResetDialog = false
                    },
                ) {
                    Text(stringResource(R.string.insights_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.insights_reset_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("insights-list"),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding(),
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 20.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.insights_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.insights_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null)
                    Text(
                        text = stringResource(R.string.insights_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricCard(
                    value = insights.totalCount,
                    label = stringResource(R.string.insights_metric_total),
                    icon = Icons.Rounded.AutoStories,
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    value = insights.readingCount,
                    label = stringResource(R.string.insights_metric_reading),
                    icon = Icons.Rounded.Schedule,
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    value = insights.finishedCount,
                    label = stringResource(R.string.insights_metric_finished),
                    icon = Icons.Rounded.CheckCircle,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            SectionTitle(stringResource(R.string.insights_ndc_title))
        }

        if (insights.ndcDistribution.isEmpty()) {
            item {
                MessageCard(stringResource(R.string.insights_ndc_empty))
            }
        } else {
            val maxCount = insights.ndcDistribution.maxOf(NdcShare::count).coerceAtLeast(1)
            insights.ndcDistribution.forEach { share ->
                item(key = "ndc-${share.digit}") {
                    ClassificationRow(share = share, progress = share.count.toFloat() / maxCount)
                }
            }
        }

        if (insights.unclassifiedCount > 0) {
            item {
                Text(
                    text = stringResource(R.string.insights_unclassified_count, insights.unclassifiedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionTitle(stringResource(R.string.insights_tsundoku_title))
            Text(
                text = stringResource(R.string.insights_tsundoku_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (insights.tsundoku.unreadCount == 0) {
            item {
                MessageCard(stringResource(R.string.insights_tsundoku_empty))
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.insights_tsundoku_unread_count, insights.tsundoku.unreadCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            insights.tsundoku.longestUnread.forEach { candidate ->
                item(key = "tsundoku-${candidate.book.copyId}") {
                    CandidateCard(
                        book = candidate.book,
                        reason = tsundokuReason(candidate),
                        onExclude = { onExcludeBook(candidate.book.copyId) },
                    )
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.insights_trend_title))
            Text(
                text = stringResource(R.string.insights_trend_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            when (val trend = insights.finishedTrend) {
                is FinishedTrendInsight.InsufficientHistory -> {
                    MessageCard(
                        stringResource(
                            R.string.insights_trend_insufficient,
                            trend.requiredSessionCount,
                            trend.datedSessionCount,
                        ),
                    )
                }

                is FinishedTrendInsight.Ready -> {
                    FinishedTrendCard(trend)
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.insights_rediscovery_title))
            Text(
                text = stringResource(R.string.insights_rediscovery_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (insights.rediscoveries.isEmpty()) {
            item {
                MessageCard(stringResource(R.string.insights_rediscovery_empty))
            }
        } else {
            insights.rediscoveries.forEach { candidate ->
                item(key = "rediscovery-${candidate.book.copyId}") {
                    CandidateCard(
                        book = candidate.book,
                        reason = rediscoveryReason(candidate),
                        onExclude = { onExcludeBook(candidate.book.copyId) },
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.insights_excluded_count, insights.excludedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { showResetDialog = true }) {
                    Text(stringResource(R.string.insights_reset_button))
                }
            }
        }

        if (classifiedBooks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle(stringResource(R.string.insights_locations_title))
            }
            items(
                count = classifiedBooks.size,
                key = { index -> classifiedBooks[index].copyId },
            ) { index ->
                val book = classifiedBooks[index]
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null)
                        Column {
                            Text(book.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(
                                    R.string.insights_location_item,
                                    book.ndcCode.orEmpty(),
                                    book.location,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun tsundokuReason(candidate: TsundokuCandidate): String = stringResource(R.string.insights_reason_unread, candidate.daysSinceAdded)

@Composable
private fun rediscoveryReason(candidate: RediscoveryCandidate): String =
    when (val reason = candidate.reason) {
        is RediscoveryReason.UnreadSinceAdded -> {
            stringResource(R.string.insights_reason_unread, reason.daysSinceAdded)
        }

        RediscoveryReason.PausedMidway -> {
            stringResource(R.string.insights_reason_paused)
        }

        is RediscoveryReason.FinishedBefore -> {
            reason.finishedDay?.let { day ->
                stringResource(R.string.insights_reason_finished_on, day.format())
            } ?: stringResource(R.string.insights_reason_finished)
        }
    }

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun MessageCard(message: String) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 候補カード。理由テキストを必ず表示し、除外操作をテキストボタンで提供する。 */
@Composable
private fun CandidateCard(
    book: LibraryBook,
    reason: String,
    onExclude: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(book.title, fontWeight = FontWeight.SemiBold)
                if (book.primaryAuthor.isNotBlank()) {
                    Text(
                        text = book.primaryAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val excludeDescription = stringResource(R.string.insights_exclude_description, book.title)
            TextButton(
                onClick = onExclude,
                modifier = Modifier.semantics { contentDescription = excludeDescription },
            ) {
                Text(stringResource(R.string.insights_exclude_button))
            }
        }
    }
}

@Composable
private fun FinishedTrendCard(trend: FinishedTrendInsight.Ready) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val first = trend.monthlyCounts.first().month
            val last = trend.monthlyCounts.last().month
            Text(
                text = stringResource(R.string.insights_trend_range, first.format(), last.format()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FinishedTrendChart(trend.monthlyCounts)
            if (trend.yearOnlyCount > 0) {
                TrendNote(stringResource(R.string.insights_trend_year_only_note, trend.yearOnlyCount))
            }
            if (trend.undatedCount > 0) {
                TrendNote(stringResource(R.string.insights_trend_undated_note, trend.undatedCount))
            }
            if (trend.outsideWindowCount > 0) {
                TrendNote(stringResource(R.string.insights_trend_outside_note, trend.outsideWindowCount))
            }
        }
    }
}

@Composable
private fun TrendNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 月別読了冊数の棒グラフ。冊数を数値テキストで併記し、各棒に年月と冊数の
 * contentDescriptionを付けて、色や高さだけに依存せず情報を取得できるようにする。
 */
@Composable
private fun FinishedTrendChart(points: List<FinishedTrendPoint>) {
    val maxCount = points.maxOf(FinishedTrendPoint::count).coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { point ->
            val description =
                stringResource(
                    R.string.insights_trend_bar_description,
                    point.month.year,
                    point.month.month,
                    point.count,
                )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = point.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
                val barHeight = (MAX_BAR_HEIGHT_DP * point.count / maxCount).coerceAtLeast(2)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.6f)
                            .height(barHeight.dp)
                            .background(
                                color =
                                    if (point.count > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                shape = RoundedCornerShape(2.dp),
                            ),
                )
                Text(
                    text = stringResource(R.string.insights_trend_month_label, point.month.month),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private const val MAX_BAR_HEIGHT_DP = 80

@Composable
private fun MetricCard(
    value: Int,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** NDC類の行。冊数と比率をテキスト併記し、行全体へ読み上げ用の説明を付ける。 */
@Composable
private fun ClassificationRow(
    share: NdcShare,
    progress: Float,
) {
    val percent = (share.ratio * 100).roundToInt()
    val rowDescription =
        stringResource(
            R.string.insights_ndc_row_description,
            share.digit,
            share.label,
            share.count,
            percent,
        )
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) { contentDescription = rowDescription },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${share.digit}  ${share.label}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.insights_ndc_share, percent),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.insights_ndc_count, share.count),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
