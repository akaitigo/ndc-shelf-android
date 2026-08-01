package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsSection
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsSnapshot
import dev.ndcshelf.app.ui.text.labelRes
import java.text.DateFormat
import java.util.Date

/**
 * 端末内診断。表示・共有する値は数値・enum・バージョンだけで、自動送信は
 * 存在しない。共有ファイルは生成前にユーザーがセクションを選択・確認する。
 */
@Composable
fun DiagnosticsScreen(
    snapshot: DiagnosticsSnapshot?,
    onClearEvents: () -> Unit,
    onGenerate: (Set<DiagnosticsSection>) -> Unit,
    contentPadding: PaddingValues,
) {
    var showSectionPicker by rememberSaveable { mutableStateOf(false) }

    if (showSectionPicker && snapshot != null) {
        DiagnosticsSectionDialog(
            onConfirm = { sections ->
                showSectionPicker = false
                onGenerate(sections)
            },
            onDismiss = { showSectionPicker = false },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.diagnostics_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (snapshot == null) {
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.diagnostics_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@LazyColumn
        }
        item {
            DiagnosticsCard(stringResource(R.string.diagnostics_section_app)) {
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_app_version),
                    "${snapshot.appVersionName} (${snapshot.appVersionCode})",
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_android_sdk),
                    snapshot.androidSdkInt.toString(),
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_database_version),
                    snapshot.databaseVersion.toString(),
                )
            }
        }
        item {
            DiagnosticsCard(stringResource(R.string.diagnostics_section_library)) {
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_count_works),
                    snapshot.workCount.toString(),
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_count_editions),
                    snapshot.editionCount.toString(),
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_count_copies),
                    snapshot.copyCount.toString(),
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_count_series),
                    snapshot.seriesCount.toString(),
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_count_scan_sessions),
                    snapshot.scanSessionCount.toString(),
                )
            }
        }
        item {
            DiagnosticsCard(stringResource(R.string.diagnostics_section_sync)) {
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_sync_enabled),
                    if (snapshot.syncEnabled) {
                        stringResource(R.string.diagnostics_value_on)
                    } else {
                        stringResource(R.string.diagnostics_value_off)
                    },
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_sync_pending),
                    snapshot.syncPendingOperations.toString(),
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_sync_conflicts),
                    snapshot.syncUnresolvedConflicts.toString(),
                )
                DiagnosticsRow(
                    stringResource(R.string.diagnostics_sync_last_success),
                    snapshot.syncLastSuccessAtMillis?.let(::formatTimestamp)
                        ?: stringResource(R.string.diagnostics_value_none),
                )
            }
        }
        item {
            DiagnosticsCard(stringResource(R.string.diagnostics_section_events)) {
                if (snapshot.recentEvents.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnostics_events_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(snapshot.recentEvents.asReversed()) { event ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(event.code.name, style = MaterialTheme.typography.bodySmall)
                Text(
                    formatTimestamp(event.timestampMillis),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showSectionPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.diagnostics_generate_button)) }
                OutlinedButton(
                    onClick = onClearEvents,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.diagnostics_clear_button)) }
            }
        }
    }
}

@Composable
private fun DiagnosticsSectionDialog(
    onConfirm: (Set<DiagnosticsSection>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable {
        mutableStateOf(DiagnosticsSection.entries.map(DiagnosticsSection::name).toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.diagnostics_picker_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.diagnostics_picker_description))
                DiagnosticsSection.entries.forEach { section ->
                    val checked = section.name in selected
                    Row(
                        modifier =
                            Modifier
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { newChecked ->
                                        selected =
                                            if (newChecked) {
                                                selected + section.name
                                            } else {
                                                selected - section.name
                                            }
                                    },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                        Text(stringResource(section.labelRes))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        DiagnosticsSection.entries
                            .filter { it.name in selected }
                            .toSet(),
                    )
                },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.diagnostics_picker_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.consent_cancel_button)) }
        },
    )
}

@Composable
private fun DiagnosticsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticsRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

private fun formatTimestamp(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

internal val DiagnosticsSection.labelRes: Int
    get() =
        when (this) {
            DiagnosticsSection.APP_AND_DEVICE -> R.string.diagnostics_picker_app
            DiagnosticsSection.LIBRARY_COUNTS -> R.string.diagnostics_picker_library
            DiagnosticsSection.SYNC_STATE -> R.string.diagnostics_picker_sync
            DiagnosticsSection.CONSENT_STATE -> R.string.diagnostics_picker_consent
            DiagnosticsSection.RECENT_EVENTS -> R.string.diagnostics_picker_events
        }
