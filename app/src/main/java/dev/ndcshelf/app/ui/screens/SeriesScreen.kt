package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesVolume
import dev.ndcshelf.app.domain.model.SeriesVolumeState
import java.text.DateFormat
import java.util.Date

@Composable
fun SeriesScreen(
    series: List<SeriesOverview>,
    selectedSeriesId: String?,
    onSelectSeries: (String?) -> Unit,
    onOpenEdition: (String) -> Unit,
    onOpenBookstore: (String) -> Unit,
    onManageSuggestions: () -> Unit = {},
    onRemoveMembership: (String) -> Unit = {},
    contentPadding: PaddingValues,
) {
    val selectedSeries = series.firstOrNull { it.series.id == selectedSeriesId }
    if (selectedSeries == null) {
        SeriesCatalog(
            series = series,
            onSelectSeries = onSelectSeries,
            onManageSuggestions = onManageSuggestions,
            contentPadding = contentPadding,
        )
    } else {
        SeriesDetail(
            overview = selectedSeries,
            onBack = { onSelectSeries(null) },
            onOpenEdition = onOpenEdition,
            onOpenBookstore = onOpenBookstore,
            onRemoveMembership = onRemoveMembership,
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun SeriesCatalog(
    series: List<SeriesOverview>,
    onSelectSeries: (String) -> Unit,
    onManageSuggestions: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SERIES_LIST_TEST_TAG),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.series_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedButton(
                onClick = onManageSuggestions,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text(stringResource(R.string.series_manage_suggestions))
            }
            Text(
                text = stringResource(R.string.series_description),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (series.isEmpty()) {
            item { SeriesEmptyState() }
        } else {
            items(series, key = { it.series.id }) { overview ->
                SeriesCatalogCard(overview = overview, onClick = { onSelectSeries(overview.series.id) })
            }
        }
    }
}

@Composable
private fun SeriesEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(SERIES_EMPTY_TEST_TAG),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.CollectionsBookmark, contentDescription = null)
            Text(
                text = stringResource(R.string.series_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.series_empty_description),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SeriesCatalogCard(overview: SeriesOverview, onClick: () -> Unit) {
    val latest = overview.latestOwnedVolume?.membership?.volumeLabel
    Card(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = overview.series.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.series_catalog_counts,
                        overview.ownedVolumeCount,
                        overview.knownVolumeCount,
                        overview.readVolumeCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (latest == null) {
                        stringResource(R.string.series_latest_owned_none)
                    } else {
                        stringResource(R.string.series_latest_owned, latest)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (overview.missingCandidateCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.series_missing_candidates,
                            overview.missingCandidateCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.series_open, overview.series.name),
            )
        }
    }
}

@Composable
private fun SeriesDetail(
    overview: SeriesOverview,
    onBack: () -> Unit,
    onOpenEdition: (String) -> Unit,
    onOpenBookstore: (String) -> Unit,
    onRemoveMembership: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val dateFormatter = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SERIES_DETAIL_TEST_TAG),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.series_back),
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = overview.series.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            R.string.series_updated_at,
                            dateFormatter.format(Date(overview.series.updatedAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SeriesSummary(overview) }
        if (overview.isConfirmedMainStoryComplete) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.series_confirmed_complete),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.series_missing_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (overview.volumes.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.series_no_volumes),
                    modifier = Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            items(overview.volumes, key = { it.membership.id }) { volume ->
                SeriesVolumeCard(volume, onOpenEdition, onOpenBookstore, onRemoveMembership)
            }
        }
    }
}

@Composable
private fun SeriesSummary(overview: SeriesOverview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SeriesMetric(
            value = overview.ownedVolumeCount.toString(),
            label = stringResource(R.string.series_owned_label),
            modifier = Modifier.weight(1f),
        )
        SeriesMetric(
            value = overview.knownVolumeCount.toString(),
            label = stringResource(R.string.series_known_label),
            modifier = Modifier.weight(1f),
        )
        SeriesMetric(
            value = overview.readVolumeCount.toString(),
            label = stringResource(R.string.series_read_label),
            modifier = Modifier.weight(1f),
        )
        SeriesMetric(
            value = overview.missingCandidateCount.toString(),
            label = stringResource(R.string.series_missing_label),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SeriesMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SeriesVolumeCard(
    volume: SeriesVolume,
    onOpenEdition: (String) -> Unit,
    onOpenBookstore: (String) -> Unit,
    onRemoveMembership: (String) -> Unit,
) {
    val stateLabel = volume.stateDescription()
    var confirmRemoval by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = stateLabel },
        colors = CardDefaults.cardColors(
            containerColor = if (volume.isMissingCandidate) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = volume.membership.volumeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = volume.membership.workTitle,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (volume.membership.primaryAuthor.isNotBlank()) {
                        Text(
                            text = volume.membership.primaryAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = volume.membership.type.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (volume.isMissingCandidate) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = when (volume.membership.origin) {
                    SeriesMembershipOrigin.TITLE_SUGGESTION ->
                        stringResource(R.string.series_origin_title_suggestion)
                    SeriesMembershipOrigin.MANUAL -> stringResource(R.string.series_origin_manual)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (volume.isMissingCandidate) {
                Text(
                    text = stringResource(R.string.series_missing_candidate),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            when {
                volume.ownedEditionId != null -> OutlinedButton(
                    onClick = { onOpenEdition(volume.ownedEditionId) },
                ) {
                    Text(stringResource(R.string.series_open_book_detail))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                }
                volume.bookstoreIsbn != null -> OutlinedButton(
                    onClick = { onOpenBookstore(volume.bookstoreIsbn) },
                ) {
                    Text(stringResource(R.string.series_open_bookstore))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                }
                else -> Text(
                    text = stringResource(R.string.series_no_isbn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { confirmRemoval = true }) {
                Text(stringResource(R.string.series_remove_membership))
            }
        }
    }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text(stringResource(R.string.series_remove_membership_title)) },
            text = { Text(stringResource(R.string.series_remove_membership_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    onRemoveMembership(volume.membership.id)
                }) { Text(stringResource(R.string.series_remove_membership_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) {
                    Text(stringResource(R.string.location_cancel))
                }
            },
        )
    }
}

@Composable
private fun SeriesVolume.stateDescription(): String = when (state) {
    SeriesVolumeState.OWNED -> when {
        isRead -> stringResource(R.string.series_state_owned_read, ownedCopyCount)
        readingCopyCount > 0 -> stringResource(R.string.series_state_owned_reading, ownedCopyCount)
        else -> stringResource(R.string.series_state_owned, ownedCopyCount)
    }
    SeriesVolumeState.WANTED -> stringResource(R.string.series_state_wanted)
    SeriesVolumeState.RESERVED -> stringResource(R.string.series_state_reserved)
    SeriesVolumeState.UNOWNED -> stringResource(R.string.series_state_unowned)
}

@Composable
private fun SeriesMembershipType.label(): String = when (this) {
    SeriesMembershipType.MAIN_STORY -> stringResource(R.string.series_type_main)
    SeriesMembershipType.SIDE_STORY -> stringResource(R.string.series_type_side_story)
    SeriesMembershipType.OMNIBUS -> stringResource(R.string.series_type_omnibus)
    SeriesMembershipType.OTHER -> stringResource(R.string.series_type_other)
}

const val SERIES_LIST_TEST_TAG = "series-list"
const val SERIES_DETAIL_TEST_TAG = "series-detail"
const val SERIES_EMPTY_TEST_TAG = "series-empty"
