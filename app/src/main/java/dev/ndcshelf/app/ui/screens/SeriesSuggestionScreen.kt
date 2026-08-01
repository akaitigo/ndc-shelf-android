package dev.ndcshelf.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.SeriesEditorUiState
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesSuggestion
import dev.ndcshelf.app.domain.model.SeriesSuggestionConfidence
import dev.ndcshelf.app.domain.model.SeriesSuggestionRule
import dev.ndcshelf.app.domain.repository.SeriesConfirmationDraft
import dev.ndcshelf.app.domain.repository.SeriesConfirmationTarget

@Composable
fun SeriesSuggestionScreen(
    suggestions: List<SeriesSuggestion>,
    catalog: List<SeriesOverview>,
    focusedSuggestion: SeriesSuggestion?,
    state: SeriesEditorUiState,
    onConfirm: (SeriesConfirmationTarget, List<SeriesConfirmationDraft>) -> Unit,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onClearState: () -> Unit,
    contentPadding: PaddingValues,
) {
    var active by remember { mutableStateOf<List<SeriesSuggestion>?>(null) }
    LaunchedEffect(focusedSuggestion?.workId) {
        if (focusedSuggestion != null) active = listOf(focusedSuggestion)
    }
    LaunchedEffect(state) {
        val saved = state as? SeriesEditorUiState.Saved ?: return@LaunchedEffect
        onClearState()
        onSaved(saved.seriesId)
    }
    BackHandler {
        if (active == null) onBack() else active = null
    }

    if (active == null) {
        SuggestionGroups(
            suggestions = suggestions,
            state = state,
            onSelect = { active = it },
            onBack = onBack,
            contentPadding = contentPadding,
        )
    } else {
        SuggestionEditor(
            source = active.orEmpty(),
            catalog = catalog,
            state = state,
            onConfirm = onConfirm,
            onBack = { active = null },
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun SuggestionGroups(
    suggestions: List<SeriesSuggestion>,
    state: SeriesEditorUiState,
    onSelect: (List<SeriesSuggestion>) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val groups =
        remember(suggestions) {
            suggestions.groupBy(SeriesSuggestion::proposedSeriesName).mapValues { (_, values) ->
                values.sortedWith(
                    compareBy<SeriesSuggestion> { it.orderHint == null }
                        .thenBy { it.orderHint }
                        .thenBy { it.sourceTitle },
                )
            }
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SERIES_SUGGESTIONS_TEST_TAG),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { EditorHeader(stringResource(R.string.series_suggestions_title), onBack) }
        item {
            Text(
                stringResource(R.string.series_suggestions_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state === SeriesEditorUiState.Loading) {
            item { CircularProgressIndicator() }
        } else if (groups.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        stringResource(R.string.series_suggestions_empty),
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                    )
                }
            }
        } else {
            groups.forEach { (name, group) ->
                item(key = name) {
                    Card(onClick = { onSelect(group) }, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(pluralStringResource(R.plurals.series_suggestion_count, group.size, group.size))
                            if (group.any { it.confidence == SeriesSuggestionConfidence.LOW }) {
                                Text(
                                    stringResource(R.string.series_low_confidence_notice),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionEditor(
    source: List<SeriesSuggestion>,
    catalog: List<SeriesOverview>,
    state: SeriesEditorUiState,
    onConfirm: (SeriesConfirmationTarget, List<SeriesConfirmationDraft>) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val fallbackVolumeLabel = stringResource(R.string.series_volume_label_none)
    var rows by remember(source.map(SeriesSuggestion::workId), fallbackVolumeLabel) {
        mutableStateOf(
            source.map { suggestion ->
                EditableSuggestion(
                    suggestion = suggestion,
                    volumeLabel = suggestion.proposedVolumeLabel ?: fallbackVolumeLabel,
                )
            },
        )
    }
    var useExisting by rememberSaveable { mutableStateOf(false) }
    var newSeriesName by rememberSaveable(source.firstOrNull()?.proposedSeriesName) {
        mutableStateOf(source.firstOrNull()?.proposedSeriesName.orEmpty())
    }
    var existingSeriesId by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state === SeriesEditorUiState.Saving
    val selectedRows = rows.filter(EditableSuggestion::included)
    val targetValid = if (useExisting) existingSeriesId != null else newSeriesName.isNotBlank()

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SERIES_EDITOR_TEST_TAG),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { EditorHeader(stringResource(R.string.series_editor_title), onBack) }
        item {
            Text(
                stringResource(R.string.series_editor_confirmation_notice),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !useExisting,
                    onClick = { useExisting = false },
                    label = { Text(stringResource(R.string.series_target_new)) },
                )
                FilterChip(
                    selected = useExisting,
                    enabled = catalog.isNotEmpty(),
                    onClick = { useExisting = true },
                    label = { Text(stringResource(R.string.series_target_existing)) },
                )
            }
        }
        if (useExisting) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(catalog, key = { it.series.id }) { overview ->
                        FilterChip(
                            selected = existingSeriesId == overview.series.id,
                            onClick = { existingSeriesId = overview.series.id },
                            label = { Text(overview.series.name) },
                        )
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = newSeriesName,
                    onValueChange = { newSeriesName = it.take(200) },
                    label = { Text(stringResource(R.string.series_name_label)) },
                    modifier = Modifier.fillMaxWidth().testTag(SERIES_NAME_FIELD_TEST_TAG),
                    singleLine = true,
                )
            }
        }
        items(rows, key = { it.suggestion.workId }) { row ->
            val index = rows.indexOfFirst { it.suggestion.workId == row.suggestion.workId }
            EditableSuggestionCard(
                row = row,
                canMoveUp = index > 0,
                canMoveDown = index < rows.lastIndex,
                onChange = { changed -> rows = rows.toMutableList().also { it[index] = changed } },
                onMoveUp = { rows = rows.moved(index, index - 1) },
                onMoveDown = { rows = rows.moved(index, index + 1) },
            )
        }
        state.messageRes()?.let { message ->
            item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) }
        }
        item {
            Button(
                enabled =
                    !busy && targetValid && selectedRows.isNotEmpty() &&
                        selectedRows.all { it.volumeLabel.isNotBlank() },
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target =
                        if (useExisting) {
                            SeriesConfirmationTarget.Existing(requireNotNull(existingSeriesId))
                        } else {
                            SeriesConfirmationTarget.New(newSeriesName)
                        }
                    onConfirm(target, selectedRows.map(EditableSuggestion::toDraft))
                },
            ) {
                if (busy) {
                    CircularProgressIndicator()
                } else {
                    Text(pluralStringResource(R.plurals.series_confirm_selected, selectedRows.size, selectedRows.size))
                }
            }
        }
    }
}

@Composable
private fun EditableSuggestionCard(
    row: EditableSuggestion,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (EditableSuggestion) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selectionDescription =
                    stringResource(
                        R.string.series_candidate_selection,
                        row.suggestion.sourceTitle,
                    )
                Checkbox(
                    checked = row.included,
                    onCheckedChange = { onChange(row.copy(included = it)) },
                    modifier = Modifier.semantics { contentDescription = selectionDescription },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.suggestion.sourceTitle, fontWeight = FontWeight.Bold)
                    Text(
                        row.suggestion.confidence.label(),
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (row.suggestion.confidence == SeriesSuggestionConfidence.LOW) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
                IconButton(enabled = canMoveUp, onClick = onMoveUp) {
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        stringResource(R.string.series_move_up, row.suggestion.sourceTitle),
                    )
                }
                IconButton(enabled = canMoveDown, onClick = onMoveDown) {
                    Icon(
                        Icons.Rounded.ArrowDownward,
                        stringResource(R.string.series_move_down, row.suggestion.sourceTitle),
                    )
                }
            }
            OutlinedTextField(
                value = row.volumeLabel,
                onValueChange = { onChange(row.copy(volumeLabel = it.take(80))) },
                enabled = row.included,
                label = { Text(stringResource(R.string.series_volume_label)) },
                modifier =
                    Modifier.fillMaxWidth().testTag(
                        SERIES_VOLUME_FIELD_TEST_TAG_PREFIX + row.suggestion.workId,
                    ),
                singleLine = true,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SeriesMembershipType.entries) { type ->
                    FilterChip(
                        selected = row.type == type,
                        enabled = row.included,
                        onClick = { onChange(row.copy(type = type)) },
                        label = { Text(type.label()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.series_editor_back))
        }
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

private data class EditableSuggestion(
    val suggestion: SeriesSuggestion,
    val included: Boolean = true,
    val volumeLabel: String,
    val type: SeriesMembershipType = suggestion.proposedType,
) {
    fun toDraft() =
        SeriesConfirmationDraft(
            workId = suggestion.workId,
            volumeLabel = volumeLabel,
            type = type,
            sourceTitle = suggestion.sourceTitle,
            origin =
                if (suggestion.rule == SeriesSuggestionRule.MANUAL_ENTRY) {
                    SeriesMembershipOrigin.MANUAL
                } else {
                    SeriesMembershipOrigin.TITLE_SUGGESTION
                },
        )
}

private fun List<EditableSuggestion>.moved(
    from: Int,
    to: Int,
): List<EditableSuggestion> = toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun SeriesSuggestionConfidence.label(): String =
    when (this) {
        SeriesSuggestionConfidence.HIGH -> stringResource(R.string.series_confidence_high)
        SeriesSuggestionConfidence.MEDIUM -> stringResource(R.string.series_confidence_medium)
        SeriesSuggestionConfidence.LOW -> stringResource(R.string.series_confidence_low)
    }

@Composable
private fun SeriesMembershipType.label(): String =
    when (this) {
        SeriesMembershipType.MAIN_STORY -> stringResource(R.string.series_type_main)
        SeriesMembershipType.SIDE_STORY -> stringResource(R.string.series_type_side_story)
        SeriesMembershipType.OMNIBUS -> stringResource(R.string.series_type_omnibus)
        SeriesMembershipType.OTHER -> stringResource(R.string.series_type_other)
    }

private fun SeriesEditorUiState.messageRes(): Int? =
    when (this) {
        SeriesEditorUiState.Conflict -> R.string.series_editor_conflict
        SeriesEditorUiState.Invalid -> R.string.series_editor_invalid
        SeriesEditorUiState.Error -> R.string.series_editor_error
        else -> null
    }

const val SERIES_SUGGESTIONS_TEST_TAG = "series-suggestions"
const val SERIES_EDITOR_TEST_TAG = "series-editor"
const val SERIES_VOLUME_FIELD_TEST_TAG_PREFIX = "series-volume-"
const val SERIES_NAME_FIELD_TEST_TAG = "series-name"
