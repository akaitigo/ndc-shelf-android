package dev.ndcshelf.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.SavedSearch
import dev.ndcshelf.app.domain.model.TagColorRole
import dev.ndcshelf.app.domain.model.TagNameRules
import dev.ndcshelf.app.domain.model.TagWithUsage

/**
 * タグ（手動コレクション）と保存済み検索（検索条件コレクション）の管理画面。
 * タグ名は信頼できない入力として、常にプレーンテキストのTextで表示する。
 */
@Composable
internal fun TagManagementScreen(
    tags: List<TagWithUsage>,
    savedSearches: List<SavedSearch>,
    onBack: () -> Unit,
    onCreateTag: (String, TagColorRole) -> Unit,
    onUpdateTag: (String, String, TagColorRole) -> Unit,
    onMergeTags: (String, String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onRenameSavedSearch: (String, String) -> Unit,
    onDeleteSavedSearch: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    BackHandler(onBack = onBack)
    var editorTagId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var mergeSourceTagId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTagId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameSearchId by rememberSaveable { mutableStateOf<String?>(null) }

    if (editorVisible) {
        val editing = tags.firstOrNull { it.tag.id == editorTagId }?.tag
        TagEditorDialog(
            initialName = editing?.name.orEmpty(),
            initialColor = editing?.colorRole ?: TagColorRole.GRAY,
            title =
                stringResource(
                    if (editing == null) R.string.tag_editor_add_title else R.string.tag_editor_edit_title,
                ),
            onSave = { name, color ->
                val target = editing
                if (target == null) onCreateTag(name, color) else onUpdateTag(target.id, name, color)
                editorVisible = false
                editorTagId = null
            },
            onDismiss = {
                editorVisible = false
                editorTagId = null
            },
        )
    }

    mergeSourceTagId?.let { sourceId ->
        val source = tags.firstOrNull { it.tag.id == sourceId }?.tag
        if (source == null) {
            mergeSourceTagId = null
        } else {
            TagMergeDialog(
                sourceName = source.name,
                candidates = tags.filter { it.tag.id != sourceId },
                onMerge = { targetId ->
                    onMergeTags(sourceId, targetId)
                    mergeSourceTagId = null
                },
                onDismiss = { mergeSourceTagId = null },
            )
        }
    }

    deleteTagId?.let { tagId ->
        val target = tags.firstOrNull { it.tag.id == tagId }
        if (target == null) {
            deleteTagId = null
        } else {
            AlertDialog(
                onDismissRequest = { deleteTagId = null },
                title = { Text(stringResource(R.string.tag_delete_confirm_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.tag_delete_confirm_message,
                            target.tag.name,
                            target.taggedWorkCount,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteTag(tagId)
                            deleteTagId = null
                        },
                    ) {
                        Text(
                            stringResource(R.string.tag_delete_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTagId = null }) {
                        Text(stringResource(R.string.tag_delete_cancel))
                    }
                },
            )
        }
    }

    renameSearchId?.let { searchId ->
        val target = savedSearches.firstOrNull { it.id == searchId }
        if (target == null) {
            renameSearchId = null
        } else {
            NameInputDialog(
                title = stringResource(R.string.saved_search_rename_title),
                label = stringResource(R.string.saved_search_name, TagNameRules.MAX_NAME_LENGTH),
                initialValue = target.name,
                onSave = { name ->
                    onRenameSavedSearch(searchId, name)
                    renameSearchId = null
                },
                onDismiss = { renameSearchId = null },
            )
        }
    }

    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.tag_management_back),
                    )
                }
                Text(
                    stringResource(R.string.tag_management_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        item {
            Text(
                stringResource(R.string.tag_privacy_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tag_section_title, tags.size, TagNameRules.MAX_TAGS),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(
                    enabled = tags.size < TagNameRules.MAX_TAGS,
                    onClick = {
                        editorTagId = null
                        editorVisible = true
                    },
                ) {
                    Text(stringResource(R.string.tag_add_action))
                }
            }
        }
        if (tags.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.tag_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(items = tags, key = { it.tag.id }) { tagWithUsage ->
            TagRow(
                tagWithUsage = tagWithUsage,
                canMerge = tags.size > 1,
                onEdit = {
                    editorTagId = tagWithUsage.tag.id
                    editorVisible = true
                },
                onMerge = { mergeSourceTagId = tagWithUsage.tag.id },
                onDelete = { deleteTagId = tagWithUsage.tag.id },
            )
        }
        item {
            Text(
                stringResource(
                    R.string.saved_search_section_title,
                    savedSearches.size,
                    TagNameRules.MAX_SAVED_SEARCHES,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (savedSearches.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.saved_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(items = savedSearches, key = SavedSearch::id) { savedSearch ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(savedSearch.name, style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = { renameSearchId = savedSearch.id }) {
                            Text(stringResource(R.string.saved_search_rename_action))
                        }
                        TextButton(onClick = { onDeleteSavedSearch(savedSearch.id) }) {
                            Text(
                                stringResource(R.string.saved_search_delete_action),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagRow(
    tagWithUsage: TagWithUsage,
    canMerge: Boolean,
    onEdit: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagColorSwatch(tagWithUsage.tag.colorRole)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tagWithUsage.tag.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${tagWithUsage.tag.colorRole.label}・" +
                            stringResource(R.string.tag_usage_count, tagWithUsage.taggedWorkCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.reading_history_edit))
                }
                if (canMerge) {
                    TextButton(onClick = onMerge) {
                        Text(stringResource(R.string.tag_merge_action))
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.tag_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagEditorDialog(
    initialName: String,
    initialColor: TagColorRole,
    title: String,
    onSave: (String, TagColorRole) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var color by rememberSaveable { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(stringResource(R.string.tag_editor_name, TagNameRules.MAX_NAME_LENGTH))
                    },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.tag_editor_color),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagColorRole.entries.forEach { candidate ->
                        FilterChip(
                            selected = color == candidate,
                            onClick = { color = candidate },
                            leadingIcon = { TagColorSwatch(candidate) },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, color) }) {
                Text(stringResource(R.string.tag_editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tag_editor_cancel))
            }
        },
    )
}

@Composable
private fun TagMergeDialog(
    sourceName: String,
    candidates: List<TagWithUsage>,
    onMerge: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_merge_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.tag_merge_description, sourceName))
                candidates.forEach { candidate ->
                    TextButton(onClick = { onMerge(candidate.tag.id) }) {
                        TagColorSwatch(candidate.tag.colorRole)
                        Spacer(Modifier.width(6.dp))
                        Text(candidate.tag.name)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tag_editor_cancel))
            }
        },
    )
}

@Composable
internal fun NameInputDialog(
    title: String,
    label: String,
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(stringResource(R.string.tag_editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tag_editor_cancel))
            }
        },
    )
}

/** 固定パレット。色だけに依存しないよう、隣接テキストで必ずラベルを併記する。 */
@Composable
internal fun TagColorSwatch(colorRole: TagColorRole) {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .size(14.dp)
                .background(colorRole.swatchColor(), CircleShape),
    )
}

internal fun TagColorRole.swatchColor(): Color =
    when (this) {
        TagColorRole.GRAY -> Color(0xFF8E9199)
        TagColorRole.RED -> Color(0xFFC94F4F)
        TagColorRole.ORANGE -> Color(0xFFD98236)
        TagColorRole.YELLOW -> Color(0xFFC7A62B)
        TagColorRole.GREEN -> Color(0xFF4F9D58)
        TagColorRole.TEAL -> Color(0xFF3B9E9A)
        TagColorRole.BLUE -> Color(0xFF4A7FD1)
        TagColorRole.PURPLE -> Color(0xFF8A63C9)
        TagColorRole.PINK -> Color(0xFFC65B8F)
        TagColorRole.BROWN -> Color(0xFF97694F)
    }
