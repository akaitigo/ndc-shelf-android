package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Dangerous
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.DatabaseBackupUiState
import dev.ndcshelf.app.ImportFailure
import dev.ndcshelf.app.LibraryImportUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportValidationError
import dev.ndcshelf.app.domain.sync.SyncEngineStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun DataManagementScreen(
    bookCount: Int,
    exportInProgress: Boolean,
    importState: LibraryImportUiState,
    databaseBackupState: DatabaseBackupUiState,
    syncStatus: SyncEngineStatus = SyncEngineStatus(),
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImportJson: () -> Unit,
    onImportCsv: () -> Unit,
    onCreateDatabaseBackup: () -> Unit,
    onSelectDatabaseBackup: () -> Unit,
    onSelectImportPolicy: (ImportConflictPolicy) -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
    onConfirmDatabaseRestore: () -> Unit,
    onDismissDatabaseBackup: () -> Unit,
    contentPadding: PaddingValues,
    onOpenConsent: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
) {
    val importBusy =
        importState === LibraryImportUiState.Loading ||
            importState === LibraryImportUiState.Applying
    val backupBusy =
        databaseBackupState === DatabaseBackupUiState.Creating ||
            databaseBackupState === DatabaseBackupUiState.Inspecting ||
            databaseBackupState === DatabaseBackupUiState.Restoring
    val anyBusy = exportInProgress || importBusy || backupBusy
    val busyReason = stringResource(R.string.data_management_busy_reason)
    val emptyReason = stringResource(R.string.data_management_empty_reason)

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(DATA_LIST_TAG),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                top = contentPadding.calculateTopPadding() + 20.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.data_management_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.data_management_description),
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.data_management_privacy_notice),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (onOpenConsent != null) {
            item {
                OutlinedButton(
                    onClick = onOpenConsent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.data_privacy_consent_button))
                }
            }
        }
        if (onOpenDiagnostics != null) {
            item {
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.data_diagnostics_button))
                }
            }
        }
        item { SectionTitle(stringResource(R.string.data_management_sync_section)) }
        item { SyncStatusCard(syncStatus) }
        item { SectionTitle(stringResource(R.string.data_management_transfer_section)) }
        item {
            DataOperationCard(
                icon = Icons.Rounded.FileDownload,
                title = stringResource(R.string.data_management_export_json_title),
                description = stringResource(R.string.data_management_export_json_description),
                actionLabel = stringResource(R.string.export_json),
                enabled = !anyBusy && bookCount > 0,
                disabledReason =
                    when {
                        anyBusy -> busyReason
                        bookCount == 0 -> emptyReason
                        else -> null
                    },
                testTag = EXPORT_JSON_TAG,
                onClick = onExportJson,
            )
        }
        item {
            DataOperationCard(
                icon = Icons.Rounded.FileDownload,
                title = stringResource(R.string.data_management_export_csv_title),
                description = stringResource(R.string.data_management_export_csv_description),
                actionLabel = stringResource(R.string.export_csv),
                enabled = !anyBusy && bookCount > 0,
                disabledReason =
                    when {
                        anyBusy -> busyReason
                        bookCount == 0 -> emptyReason
                        else -> null
                    },
                testTag = EXPORT_CSV_TAG,
                onClick = onExportCsv,
            )
        }
        item {
            DataOperationCard(
                icon = Icons.Rounded.FileUpload,
                title = stringResource(R.string.data_management_import_json_title),
                description = stringResource(R.string.data_management_import_json_description),
                actionLabel = stringResource(R.string.import_json),
                enabled = !anyBusy,
                disabledReason = busyReason.takeIf { anyBusy },
                testTag = IMPORT_JSON_TAG,
                onClick = onImportJson,
            )
        }
        item {
            DataOperationCard(
                icon = Icons.Rounded.FileUpload,
                title = stringResource(R.string.data_management_import_csv_title),
                description = stringResource(R.string.data_management_import_csv_description),
                actionLabel = stringResource(R.string.import_csv),
                enabled = !anyBusy,
                disabledReason = busyReason.takeIf { anyBusy },
                testTag = IMPORT_CSV_TAG,
                onClick = onImportCsv,
            )
        }
        item { SectionTitle(stringResource(R.string.data_management_backup_section)) }
        item {
            DataOperationCard(
                icon = Icons.Rounded.Backup,
                title = stringResource(R.string.data_management_backup_title),
                description = stringResource(R.string.data_management_backup_description),
                actionLabel = stringResource(R.string.database_backup_create),
                enabled = !anyBusy && bookCount > 0,
                disabledReason =
                    when {
                        anyBusy -> busyReason
                        bookCount == 0 -> emptyReason
                        else -> null
                    },
                testTag = BACKUP_TAG,
                onClick = onCreateDatabaseBackup,
            )
        }
        item {
            Text(
                text = stringResource(R.string.data_management_destructive_section),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        item {
            DataOperationCard(
                icon = Icons.Rounded.Dangerous,
                title = stringResource(R.string.data_management_restore_title),
                description = stringResource(R.string.data_management_restore_description),
                actionLabel = stringResource(R.string.database_backup_restore_select),
                enabled = !anyBusy,
                disabledReason = busyReason.takeIf { anyBusy },
                destructive = true,
                testTag = RESTORE_TAG,
                onClick = onSelectDatabaseBackup,
            )
        }
    }

    if (exportInProgress) {
        DataProgressDialog(stringResource(R.string.data_management_exporting))
    }
    when (databaseBackupState) {
        DatabaseBackupUiState.Idle,
        is DatabaseBackupUiState.Created,
        is DatabaseBackupUiState.Restored,
        -> {
            Unit
        }

        DatabaseBackupUiState.Creating -> {
            DataProgressDialog(
                stringResource(R.string.database_backup_creating),
            )
        }

        DatabaseBackupUiState.Inspecting -> {
            DataProgressDialog(
                stringResource(R.string.database_backup_inspecting),
            )
        }

        DatabaseBackupUiState.Restoring -> {
            DataProgressDialog(
                stringResource(R.string.database_backup_restoring),
            )
        }

        is DatabaseBackupUiState.Preview -> {
            DatabaseRestorePreviewDialog(
                state = databaseBackupState,
                onConfirm = onConfirmDatabaseRestore,
                onDismiss = onDismissDatabaseBackup,
            )
        }

        is DatabaseBackupUiState.Error -> {
            DatabaseBackupErrorDialog(
                state = databaseBackupState,
                onDismiss = onDismissDatabaseBackup,
            )
        }
    }
    when (importState) {
        LibraryImportUiState.Idle,
        is LibraryImportUiState.Success,
        -> {
            Unit
        }

        LibraryImportUiState.Loading -> {
            ImportProgressDialog(
                message = stringResource(R.string.import_loading),
                onCancel = onDismissImport,
            )
        }

        LibraryImportUiState.Applying -> {
            ImportProgressDialog(
                message = stringResource(R.string.import_applying),
                onCancel = onDismissImport,
            )
        }

        is LibraryImportUiState.Invalid -> {
            ImportErrorDialog(
                errors = importState.errors,
                onDismiss = onDismissImport,
            )
        }

        is LibraryImportUiState.Error -> {
            ImportErrorDialog(
                errors =
                    listOf(
                        ImportValidationError(null, null, importFailureMessage(importState.failure)),
                    ),
                onDismiss = onDismissImport,
            )
        }

        is LibraryImportUiState.Preview -> {
            ImportPreviewDialog(
                state = importState,
                onSelectPolicy = onSelectImportPolicy,
                onConfirm = onConfirmImport,
                onDismiss = onDismissImport,
            )
        }
    }
}

@Composable
private fun SyncStatusCard(status: SyncEngineStatus) {
    val lastSuccessful =
        remember(status.lastSuccessfulAt) {
            status.lastSuccessfulAt?.let { timestamp ->
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
            }
        }
    val detail =
        when {
            status.requiresReregistration -> {
                stringResource(R.string.sync_status_requires_reregistration)
            }

            !status.enabled -> {
                stringResource(R.string.sync_status_off)
            }

            else -> {
                stringResource(
                    R.string.sync_status_on,
                    status.pendingOperationCount,
                    status.unresolvedConflictCount,
                    lastSuccessful ?: stringResource(R.string.sync_status_never),
                )
            }
        }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(SYNC_STATUS_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Sync, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.sync_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 10.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun DataOperationCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String,
    enabled: Boolean,
    disabledReason: String?,
    testTag: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val colors =
        if (destructive) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = colors,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (!enabled && disabledReason != null) {
                Text(
                    text = disabledReason,
                    modifier = Modifier.testTag("${testTag}_reason"),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (destructive) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Button(
                onClick = onClick,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(testTag),
                enabled = enabled,
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun DataProgressDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        icon = { CircularProgressIndicator(modifier = Modifier.size(40.dp)) },
        title = { Text(message) },
        confirmButton = {},
    )
}

@Composable
private fun DatabaseRestorePreviewDialog(
    state: DatabaseBackupUiState.Preview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val date =
        remember(state.metadata.createdAt) {
            java.text.DateFormat
                .getDateTimeInstance()
                .format(java.util.Date(state.metadata.createdAt))
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = null) },
        title = { Text(stringResource(R.string.database_restore_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.database_restore_preview_metadata,
                        date,
                        state.metadata.appVersion,
                        state.metadata.databaseVersion,
                        state.metadata.copyCount,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.database_restore_preview_warning))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.database_restore_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) }
        },
    )
}

@Composable
private fun DatabaseBackupErrorDialog(
    state: DatabaseBackupUiState.Error,
    onDismiss: () -> Unit,
) {
    val message =
        when (state.failure) {
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.READ_FAILED -> R.string.database_backup_error_read
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.WRITE_FAILED -> R.string.database_backup_error_write
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.TOO_LARGE -> R.string.database_backup_error_too_large
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.INVALID_ARCHIVE -> R.string.database_backup_error_archive
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.CHECKSUM_MISMATCH -> R.string.database_backup_error_checksum
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.UNSUPPORTED_FORMAT -> R.string.database_backup_error_format
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.NEWER_DATABASE -> R.string.database_backup_error_newer
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.INTEGRITY_FAILED -> R.string.database_backup_error_integrity
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.INSUFFICIENT_SPACE -> R.string.database_backup_error_space
            dev.ndcshelf.app.domain.backup.DatabaseBackupFailure.RESTORE_FAILED -> R.string.database_backup_error_restore
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.database_backup_error_title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_close)) }
        },
    )
}

@Composable
private fun ImportProgressDialog(
    message: String,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { CircularProgressIndicator() },
        title = { Text(message) },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.import_cancel)) }
        },
    )
}

@Composable
private fun ImportErrorDialog(
    errors: List<ImportValidationError>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_error_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                errors.forEach { error ->
                    val text =
                        when {
                            error.recordNumber != null && error.field != null -> {
                                stringResource(
                                    R.string.import_error_location,
                                    error.recordNumber,
                                    error.field,
                                    error.reason,
                                )
                            }

                            error.field != null -> {
                                stringResource(
                                    R.string.import_error_root,
                                    error.field,
                                    error.reason,
                                )
                            }

                            else -> {
                                stringResource(R.string.import_error_general, error.reason)
                            }
                        }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_close)) }
        },
    )
}

@Composable
private fun ImportPreviewDialog(
    state: LibraryImportUiState.Preview,
    onSelectPolicy: (ImportConflictPolicy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FileUpload, contentDescription = null) },
        title = { Text(stringResource(R.string.import_preview_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.staleRecalculated) {
                    Text(
                        stringResource(R.string.import_stale_notice),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(stringResource(R.string.import_preview_description))
                Text(
                    stringResource(
                        R.string.import_preview_counts,
                        state.addedCount,
                        state.updatedCount,
                        state.skippedCount,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                if (state.warnings.isNotEmpty()) {
                    Text(
                        stringResource(R.string.import_warning_title),
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                    state.warnings.forEach { warning ->
                        Text(
                            if (warning.field == null) {
                                warning.reason
                            } else {
                                stringResource(
                                    R.string.import_error_root,
                                    warning.field,
                                    warning.reason,
                                )
                            },
                        )
                    }
                }
                Column(Modifier.selectableGroup()) {
                    ImportPolicyOption(
                        selected = state.conflictPolicy == ImportConflictPolicy.SKIP_EXISTING,
                        label = stringResource(R.string.import_policy_skip),
                        onClick = { onSelectPolicy(ImportConflictPolicy.SKIP_EXISTING) },
                    )
                    ImportPolicyOption(
                        selected = state.conflictPolicy == ImportConflictPolicy.UPDATE_EXISTING,
                        label = stringResource(R.string.import_policy_update),
                        onClick = { onSelectPolicy(ImportConflictPolicy.UPDATE_EXISTING) },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = state.changeCount > 0) {
                Text(stringResource(R.string.import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_cancel)) }
        },
    )
}

@Composable
private fun ImportPolicyOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun importFailureMessage(failure: ImportFailure): String =
    when (failure) {
        ImportFailure.JSON_READ -> stringResource(R.string.import_read_failure)
        ImportFailure.CSV_READ -> stringResource(R.string.import_csv_read_failure)
        ImportFailure.PREVIEW -> stringResource(R.string.import_preview_failure)
        ImportFailure.APPLY -> stringResource(R.string.import_apply_failure)
        ImportFailure.STALE_RESELECT -> stringResource(R.string.import_stale_reselect)
    }

internal const val EXPORT_JSON_TAG = "data_export_json"
internal const val EXPORT_CSV_TAG = "data_export_csv"
internal const val IMPORT_JSON_TAG = "data_import_json"
internal const val IMPORT_CSV_TAG = "data_import_csv"
internal const val BACKUP_TAG = "data_database_backup"
internal const val SYNC_STATUS_TAG = "data_sync_status"
internal const val RESTORE_TAG = "data_database_restore"
internal const val DATA_LIST_TAG = "data_management_list"
