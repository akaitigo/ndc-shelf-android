package dev.ndcshelf.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.WorkVariantUiState
import dev.ndcshelf.app.domain.model.EditionVariant
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.WorkVariant
import dev.ndcshelf.app.domain.model.WorkVariantSuggestion
import dev.ndcshelf.app.ui.text.resolve

@Composable
internal fun WorkVariantScreen(
    state: WorkVariantUiState,
    onBack: () -> Unit,
    onLink: (String, Boolean) -> Unit,
    onUnlink: (String) -> Unit,
    onSetSeriesSubstitution: (String, Boolean) -> Unit,
    contentPadding: PaddingValues,
) {
    BackHandler(onBack = onBack)
    val ready = state as? WorkVariantUiState.Ready
    var selected by remember { mutableStateOf<WorkVariantSuggestion?>(null) }
    var unlinkMembershipId by remember { mutableStateOf<String?>(null) }
    var proposedSubstitution by rememberSaveable(ready?.editor?.group?.id) {
        mutableStateOf(ready?.editor?.group?.seriesSubstitutionEnabled ?: false)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.work_variant_back),
                    )
                }
                Text(
                    stringResource(R.string.work_variant_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        when (state) {
            WorkVariantUiState.Idle, WorkVariantUiState.Loading, WorkVariantUiState.Saving -> {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.work_variant_loading))
                    }
                }
            }

            WorkVariantUiState.Conflict,
            WorkVariantUiState.Invalid,
            WorkVariantUiState.Error,
            -> {
                item {
                    val message =
                        when (state) {
                            WorkVariantUiState.Conflict -> R.string.work_variant_conflict
                            WorkVariantUiState.Invalid -> R.string.work_variant_invalid
                            else -> R.string.work_variant_error
                        }
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                }
            }

            is WorkVariantUiState.Ready -> {
                item {
                    Text(
                        stringResource(R.string.work_variant_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    WorkVariantCard(state.editor.source)
                }
                state.editor.group?.let { group ->
                    item {
                        SectionTitle(stringResource(R.string.work_variant_current))
                        SettingRow(
                            checked = group.seriesSubstitutionEnabled,
                            onCheckedChange = { onSetSeriesSubstitution(group.id, it) },
                        )
                    }
                    items(state.editor.groupMembers, key = WorkVariant::workId) { member ->
                        // 非クリッカブルCard内のタイトル・著者・版情報を1ストップで
                        // 読み上げるためmergeする。解除ボタンは個別ノードのまま残る。
                        Card(
                            modifier = Modifier.semantics(mergeDescendants = true) {},
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                WorkVariantCard(member)
                                if (member.workId != state.editor.source.workId) {
                                    OutlinedButton(
                                        onClick = { unlinkMembershipId = member.membership?.id },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.work_variant_unlink))
                                    }
                                }
                            }
                        }
                    }
                } ?: item {
                    SettingRow(
                        checked = proposedSubstitution,
                        onCheckedChange = { proposedSubstitution = it },
                    )
                }
                item { SectionTitle(stringResource(R.string.work_variant_candidates)) }
                if (state.editor.suggestions.isEmpty()) {
                    item { Text(stringResource(R.string.work_variant_empty)) }
                } else {
                    items(state.editor.suggestions, key = { it.work.workId }) { suggestion ->
                        Card(
                            onClick = { selected = suggestion },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    suggestion.reason.resolve(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(6.dp))
                                WorkVariantCard(suggestion.work)
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { suggestion ->
        val editor = ready?.editor ?: return@let
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.work_variant_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WorkVariantCard(editor.source)
                    HorizontalDivider()
                    WorkVariantCard(suggestion.work)
                    Text(
                        stringResource(R.string.work_variant_confirm_impact),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    selected = null
                    onLink(
                        suggestion.work.workId,
                        editor.group?.seriesSubstitutionEnabled ?: proposedSubstitution,
                    )
                }) { Text(stringResource(R.string.work_variant_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.work_variant_cancel))
                }
            },
        )
    }

    unlinkMembershipId?.let { membershipId ->
        AlertDialog(
            onDismissRequest = { unlinkMembershipId = null },
            title = { Text(stringResource(R.string.work_variant_unlink_title)) },
            text = { Text(stringResource(R.string.work_variant_unlink_message)) },
            confirmButton = {
                Button(onClick = {
                    unlinkMembershipId = null
                    onUnlink(membershipId)
                }) { Text(stringResource(R.string.work_variant_unlink)) }
            },
            dismissButton = {
                TextButton(onClick = { unlinkMembershipId = null }) {
                    Text(stringResource(R.string.work_variant_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        // Switch単体では対象が読み上げられないため、行全体をtoggleableにして
        // ラベル・説明・状態を1ノードへ統合する（Switch自身のクリックは無効化）。
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    ).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.work_variant_series_substitution), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.work_variant_series_substitution_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun WorkVariantCard(work: WorkVariant) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(work.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(work.primaryAuthor, color = MaterialTheme.colorScheme.onSurfaceVariant)
        work.editions.forEach { EditionLine(it) }
    }
}

@Composable
private fun EditionLine(edition: EditionVariant) {
    val unknown = stringResource(R.string.work_variant_unknown)
    val physical = stringResource(R.string.book_detail_media_physical)
    val digital = stringResource(R.string.book_detail_media_digital)
    val media =
        edition.mediaTypes
            .joinToString("/") {
                when (it) {
                    MediaType.PHYSICAL -> physical
                    MediaType.DIGITAL -> digital
                }
            }.ifEmpty { unknown }
    Text(
        stringResource(
            R.string.work_variant_edition_meta,
            edition.isbn13 ?: unknown,
            edition.publisher ?: unknown,
            edition.publishedYear?.toString() ?: unknown,
            edition.ndcCode ?: unknown,
            if (edition.coverUrl == null) {
                stringResource(R.string.work_variant_cover_absent)
            } else {
                stringResource(R.string.work_variant_cover_present)
            },
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(
            R.string.work_variant_edition_summary,
            media,
            pluralStringResource(
                R.plurals.work_variant_owned,
                edition.ownedCopyCount,
                edition.ownedCopyCount,
            ),
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionTitle(value: String) {
    Text(
        value,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}
