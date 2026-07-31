package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.SyncSettingsViewModel
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncConfigurationStatus
import dev.ndcshelf.app.domain.sync.SyncDeviceInfo
import dev.ndcshelf.app.domain.sync.SyncFailure
import dev.ndcshelf.app.domain.sync.SyncFailureReason
import dev.ndcshelf.app.domain.sync.SyncJoinCandidate
import java.text.DateFormat
import java.util.Date

/** 同期の有効化mode。SAFフォルダ選択後にcreate/joinへ分岐する。 */
enum class SyncEnableMode { CREATE, JOIN }

/**
 * データ管理画面の同期セクション（Issue #38）。同期は既定OFFで、
 * ConsentPurpose.LIBRARY_SYNCの同意とフォルダ選択を経てだけ有効化できる。
 */
@Composable
fun SyncSettingsSection(
    configuration: SyncConfigurationStatus,
    devices: List<SyncDeviceInfo>,
    uiState: SyncSettingsViewModel.SyncUiState,
    consentGranted: Boolean,
    onGrantConsent: () -> Unit,
    onStartCreate: () -> Unit,
    onStartJoin: (String) -> Unit,
    onSyncNow: () -> Unit,
    onCompleteJoin: () -> Unit,
    onCreateInvite: () -> Unit,
    onRefreshJoinCandidates: () -> Unit,
    onApproveJoin: (SyncJoinCandidate) -> Unit,
    onRevokeDevice: (String) -> Unit,
    onPurgeRemote: () -> Unit,
    onStopSync: () -> Unit,
    onDismissInvite: () -> Unit,
    onDismissReceipt: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    var consentAction by rememberSaveable { mutableStateOf<SyncEnableMode?>(null) }
    var joinCodeDialog by rememberSaveable { mutableStateOf(false) }
    var joinCodeInput by rememberSaveable { mutableStateOf("") }
    var revokeTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmPurge by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    consentAction?.let { action ->
        ConsentPayloadDialog(
            purpose = ConsentPurpose.LIBRARY_SYNC,
            payloadItems = listOf(stringResource(R.string.sync_consent_payload_item)),
            onAccept = {
                onGrantConsent()
                consentAction = null
                when (action) {
                    SyncEnableMode.CREATE -> onStartCreate()
                    SyncEnableMode.JOIN -> joinCodeDialog = true
                }
            },
            onDismiss = { consentAction = null },
        )
    }

    if (joinCodeDialog) {
        AlertDialog(
            onDismissRequest = { joinCodeDialog = false },
            title = { Text(stringResource(R.string.sync_join_code_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sync_join_code_description))
                    OutlinedTextField(
                        value = joinCodeInput,
                        onValueChange = { joinCodeInput = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_JOIN_CODE_INPUT_TAG),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        joinCodeDialog = false
                        onStartJoin(joinCodeInput.trim())
                    },
                    enabled = joinCodeInput.isNotBlank(),
                ) { Text(stringResource(R.string.sync_join_code_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { joinCodeDialog = false }) {
                    Text(stringResource(R.string.import_cancel))
                }
            },
        )
    }

    revokeTarget?.let { deviceId ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(stringResource(R.string.sync_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.sync_revoke_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRevokeDevice(deviceId)
                        revokeTarget = null
                    },
                ) { Text(stringResource(R.string.sync_revoke_button)) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) {
                    Text(stringResource(R.string.import_cancel))
                }
            },
        )
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text(stringResource(R.string.sync_purge_confirm_title)) },
            text = { Text(stringResource(R.string.sync_purge_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onPurgeRemote()
                        confirmPurge = false
                    },
                ) { Text(stringResource(R.string.sync_purge_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = false }) {
                    Text(stringResource(R.string.import_cancel))
                }
            },
        )
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.sync_stop_confirm_title)) },
            text = { Text(stringResource(R.string.sync_stop_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onStopSync()
                        confirmStop = false
                    },
                ) { Text(stringResource(R.string.sync_stop_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.import_cancel))
                }
            },
        )
    }

    uiState.inviteCode?.let { inviteCode ->
        AlertDialog(
            onDismissRequest = onDismissInvite,
            title = { Text(stringResource(R.string.sync_invite_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sync_invite_description))
                    Text(
                        inviteCode,
                        modifier = Modifier.testTag(SYNC_INVITE_CODE_TAG),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (uiState.joinCandidates.isEmpty()) {
                        Text(stringResource(R.string.sync_invite_waiting))
                    }
                    uiState.joinCandidates.forEach { candidate ->
                        Card(colors = CardDefaults.cardColors()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    candidate.deviceName.ifBlank {
                                        stringResource(R.string.sync_device_unnamed)
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    stringResource(
                                        R.string.sync_verification_code,
                                        candidate.verificationCode,
                                    ),
                                )
                                Text(stringResource(R.string.sync_verification_hint))
                                Button(
                                    onClick = { onApproveJoin(candidate) },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag(SYNC_APPROVE_TAG),
                                    enabled = !uiState.busy,
                                ) { Text(stringResource(R.string.sync_approve_button)) }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = onRefreshJoinCandidates,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_invite_refresh)) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissInvite) { Text(stringResource(R.string.import_close)) }
            },
        )
    }

    uiState.deletionReceipt?.let { receipt ->
        AlertDialog(
            onDismissRequest = onDismissReceipt,
            title = { Text(stringResource(R.string.sync_purge_receipt_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (receipt.remainingObjectCount == 0) {
                            stringResource(R.string.sync_purge_receipt_complete)
                        } else {
                            stringResource(
                                R.string.sync_purge_receipt_incomplete,
                                receipt.remainingObjectCount,
                            )
                        },
                    )
                    Text(receipt.physicalDeletionNote, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissReceipt) { Text(stringResource(R.string.import_close)) }
            },
        )
    }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(SYNC_SECTION_TAG),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            uiState.lastFailure?.let { failure ->
                Text(
                    text = failureMessage(failure),
                    modifier = Modifier.testTag(SYNC_ERROR_TAG),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onDismissFailure) {
                    Text(stringResource(R.string.import_close))
                }
            }
            configuration.securityLockout?.let {
                Text(
                    text = stringResource(R.string.sync_security_lockout),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when {
                !configuration.configured -> {
                    Text(
                        stringResource(R.string.sync_setup_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            if (consentGranted) {
                                onStartCreate()
                            } else {
                                consentAction = SyncEnableMode.CREATE
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_ENABLE_TAG),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_enable_button)) }
                    OutlinedButton(
                        onClick = {
                            if (consentGranted) {
                                joinCodeDialog = true
                            } else {
                                consentAction = SyncEnableMode.JOIN
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_JOIN_TAG),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_join_button)) }
                }

                configuration.joinPending -> {
                    Text(
                        stringResource(R.string.sync_join_pending_description),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    uiState.joinVerificationCode?.let { code ->
                        Text(
                            stringResource(R.string.sync_verification_code, code),
                            modifier = Modifier.testTag(SYNC_JOIN_VERIFICATION_TAG),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.sync_verification_hint))
                    }
                    Button(
                        onClick = onCompleteJoin,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_COMPLETE_JOIN_TAG),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_complete_join_button)) }
                    OutlinedButton(
                        onClick = { confirmStop = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_stop_button)) }
                }

                else -> {
                    if (!configuration.hardwareBackedKeys) {
                        Text(
                            stringResource(R.string.sync_software_keystore_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = onSyncNow,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_NOW_TAG),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_now_button)) }
                    OutlinedButton(
                        onClick = {
                            onCreateInvite()
                            onRefreshJoinCandidates()
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_ADD_DEVICE_TAG),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_add_device_button)) }
                    Text(
                        stringResource(R.string.sync_devices_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    devices.forEach { device ->
                        SyncDeviceRow(
                            device = device,
                            busy = uiState.busy,
                            onRevoke = { revokeTarget = device.deviceId },
                        )
                    }
                    OutlinedButton(
                        onClick = { confirmPurge = true },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_PURGE_TAG),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_purge_button)) }
                    OutlinedButton(
                        onClick = { confirmStop = true },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(SYNC_STOP_TAG),
                        enabled = !uiState.busy,
                    ) { Text(stringResource(R.string.sync_stop_button)) }
                }
            }
        }
    }
}

@Composable
private fun SyncDeviceRow(
    device: SyncDeviceInfo,
    busy: Boolean,
    onRevoke: () -> Unit,
) {
    val lastSync =
        device.lastSyncAtMillis?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        } ?: stringResource(R.string.sync_status_never)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            Text(
                text =
                    buildString {
                        append(device.name.ifBlank { stringResource(R.string.sync_device_unnamed) })
                        if (device.isSelf) append(stringResource(R.string.sync_device_self_suffix))
                    },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    if (device.revoked) {
                        stringResource(R.string.sync_device_revoked)
                    } else {
                        stringResource(R.string.sync_device_last_sync, lastSync)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!device.isSelf && !device.revoked) {
            TextButton(
                onClick = onRevoke,
                enabled = !busy,
                modifier = Modifier.testTag("${SYNC_REVOKE_TAG_PREFIX}${device.deviceId}"),
            ) { Text(stringResource(R.string.sync_revoke_button)) }
        }
    }
}

@Composable
private fun failureMessage(failure: SyncFailure): String =
    when (failure.reason) {
        SyncFailureReason.CONSENT_REQUIRED -> stringResource(R.string.sync_error_consent)
        SyncFailureReason.NOT_ENABLED -> stringResource(R.string.sync_error_not_enabled)
        SyncFailureReason.ALREADY_ENABLED -> stringResource(R.string.sync_error_already_enabled)
        SyncFailureReason.LIBRARY_ALREADY_EXISTS -> stringResource(R.string.sync_error_library_exists)
        SyncFailureReason.LIBRARY_NOT_FOUND -> stringResource(R.string.sync_error_library_missing)
        SyncFailureReason.INVITE_INVALID -> stringResource(R.string.sync_error_invite_invalid)
        SyncFailureReason.JOIN_NOT_READY -> stringResource(R.string.sync_error_join_not_ready)
        SyncFailureReason.JOIN_REQUEST_NOT_FOUND -> stringResource(R.string.sync_error_join_not_ready)
        SyncFailureReason.SECURITY_LOCKOUT -> stringResource(R.string.sync_security_lockout)
        SyncFailureReason.KEY_UNAVAILABLE -> stringResource(R.string.sync_error_key_unavailable)
        SyncFailureReason.DEVICE_REVOKED -> stringResource(R.string.sync_error_device_revoked)
        SyncFailureReason.COUNTER_EXHAUSTED -> stringResource(R.string.sync_error_internal)
        SyncFailureReason.INCOMPATIBLE_BACKEND -> stringResource(R.string.sync_error_backend_incompatible)
        SyncFailureReason.BACKEND -> backendErrorMessage(failure.backendKind)
        SyncFailureReason.INTERNAL -> stringResource(R.string.sync_error_internal)
    }

@Composable
private fun backendErrorMessage(kind: SyncBackendErrorKind?): String =
    when (kind) {
        SyncBackendErrorKind.NETWORK -> stringResource(R.string.sync_error_network)

        SyncBackendErrorKind.TLS_FAILURE -> stringResource(R.string.sync_error_tls)

        SyncBackendErrorKind.AUTHENTICATION_FAILED,
        SyncBackendErrorKind.TOKEN_EXPIRED,
        -> stringResource(R.string.sync_error_auth)

        SyncBackendErrorKind.RATE_LIMITED -> stringResource(R.string.sync_error_rate_limited)

        SyncBackendErrorKind.SERVICE_UNAVAILABLE -> stringResource(R.string.sync_error_unavailable)

        SyncBackendErrorKind.PERMISSION_LOST -> stringResource(R.string.sync_error_permission_lost)

        SyncBackendErrorKind.STORAGE_FULL -> stringResource(R.string.sync_error_storage_full)

        SyncBackendErrorKind.CAS_CONFLICT -> stringResource(R.string.sync_error_conflict_retry)

        SyncBackendErrorKind.NOT_FOUND,
        SyncBackendErrorKind.INVALID_RESPONSE,
        SyncBackendErrorKind.INCOMPATIBLE_CAPABILITY,
        -> stringResource(R.string.sync_error_backend_incompatible)

        SyncBackendErrorKind.IO_FAILURE, null -> stringResource(R.string.sync_error_io)
    }

internal const val SYNC_SECTION_TAG = "sync_settings_section"
internal const val SYNC_ENABLE_TAG = "sync_enable"
internal const val SYNC_JOIN_TAG = "sync_join"
internal const val SYNC_JOIN_CODE_INPUT_TAG = "sync_join_code_input"
internal const val SYNC_NOW_TAG = "sync_now"
internal const val SYNC_ADD_DEVICE_TAG = "sync_add_device"
internal const val SYNC_COMPLETE_JOIN_TAG = "sync_complete_join"
internal const val SYNC_JOIN_VERIFICATION_TAG = "sync_join_verification"
internal const val SYNC_INVITE_CODE_TAG = "sync_invite_code"
internal const val SYNC_APPROVE_TAG = "sync_approve"
internal const val SYNC_PURGE_TAG = "sync_purge"
internal const val SYNC_STOP_TAG = "sync_stop"
internal const val SYNC_ERROR_TAG = "sync_error"
internal const val SYNC_REVOKE_TAG_PREFIX = "sync_revoke_"
