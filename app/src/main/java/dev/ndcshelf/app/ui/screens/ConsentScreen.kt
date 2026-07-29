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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 目的別同意の状態確認・付与・撤回画面。外部通信する任意機能は全て既定OFFで、
 * この画面と各機能の初回有効化時だけが同意の入口になる。
 */
@Composable
fun ConsentScreen(
    consents: Map<ConsentPurpose, ConsentRecord>,
    payloadPreviewItems: List<String>,
    onGrant: (ConsentPurpose) -> Unit,
    onRevoke: (ConsentPurpose) -> Unit,
    contentPadding: PaddingValues,
) {
    var previewPurpose by rememberSaveable { mutableStateOf<ConsentPurpose?>(null) }
    var revokePurpose by rememberSaveable { mutableStateOf<ConsentPurpose?>(null) }

    previewPurpose?.let { purpose ->
        ConsentPayloadDialog(
            purpose = purpose,
            payloadItems = payloadPreviewItems,
            onAccept = {
                onGrant(purpose)
                previewPurpose = null
            },
            onDismiss = { previewPurpose = null },
        )
    }

    revokePurpose?.let { purpose ->
        AlertDialog(
            onDismissRequest = { revokePurpose = null },
            title = { Text(stringResource(R.string.consent_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.consent_revoked_notice)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRevoke(purpose)
                        revokePurpose = null
                    },
                ) { Text(stringResource(R.string.consent_revoke_button)) }
            },
            dismissButton = {
                TextButton(onClick = { revokePurpose = null }) {
                    Text(stringResource(R.string.consent_cancel_button))
                }
            },
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
                    text = stringResource(R.string.consent_screen_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.consent_screen_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        items(AVAILABLE_PURPOSES) { purpose ->
            ConsentPurposeCard(
                purpose = purpose,
                record = consents[purpose],
                onRequestGrant = { previewPurpose = purpose },
                onRequestRevoke = { revokePurpose = purpose },
            )
        }
    }
}

@Composable
private fun ConsentPurposeCard(
    purpose: ConsentPurpose,
    record: ConsentRecord?,
    onRequestGrant: () -> Unit,
    onRequestRevoke: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(purpose.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = consentStatusText(purpose, record),
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (record?.granted == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            ConsentDetailRow(R.string.consent_detail_purpose_label, purpose.purposeRes)
            ConsentDetailRow(R.string.consent_detail_destination_label, purpose.destinationRes)
            ConsentDetailRow(R.string.consent_detail_items_label, purpose.itemsRes)
            ConsentDetailRow(R.string.consent_detail_retention_label, purpose.retentionRes)
            ConsentDetailRow(R.string.consent_detail_third_party_label, purpose.thirdPartyRes)
            if (record?.granted == true) {
                OutlinedButton(onClick = onRequestRevoke, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.consent_revoke_button))
                }
            } else {
                if (record.requiresReconsent(purpose)) {
                    Text(
                        text = stringResource(R.string.consent_reconsent_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = onRequestGrant, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.consent_grant_button))
                }
            }
        }
    }
}

@Composable
private fun ConsentDetailRow(
    labelRes: Int,
    valueRes: Int,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = stringResource(valueRes),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 初回送信前に実際のペイロード項目を提示する確認ダイアログ。
 * 同意とキャンセルは同じ視覚的重みで表示し、既定選択を持たない。
 */
@Composable
fun ConsentPayloadDialog(
    purpose: ConsentPurpose,
    payloadItems: List<String>,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.consent_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(purpose.titleRes), fontWeight = FontWeight.Bold)
                ConsentDetailRow(R.string.consent_detail_destination_label, purpose.destinationRes)
                ConsentDetailRow(R.string.consent_detail_items_label, purpose.itemsRes)
                ConsentDetailRow(R.string.consent_detail_retention_label, purpose.retentionRes)
                ConsentDetailRow(R.string.consent_detail_third_party_label, purpose.thirdPartyRes)
                if (payloadItems.isEmpty()) {
                    Text(stringResource(R.string.consent_preview_empty))
                } else {
                    Text(stringResource(R.string.consent_preview_description))
                    payloadItems.take(MAX_PREVIEW_ITEMS).forEach { item ->
                        Text("・$item", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (payloadItems.size > MAX_PREVIEW_ITEMS) {
                        Text(
                            stringResource(
                                R.string.consent_preview_more,
                                payloadItems.size - MAX_PREVIEW_ITEMS,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text(stringResource(R.string.consent_accept_button)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.consent_cancel_button))
            }
        },
    )
}

private fun ConsentRecord?.requiresReconsent(purpose: ConsentPurpose): Boolean =
    this != null &&
        grantedAtMillis != null &&
        revokedAtMillis == null &&
        consentedVersion < purpose.policyVersion

@Composable
private fun consentStatusText(
    purpose: ConsentPurpose,
    record: ConsentRecord?,
): String =
    when {
        record?.granted == true -> {
            stringResource(
                R.string.consent_status_granted,
                formatConsentDate(requireNotNull(record.grantedAtMillis)),
                record.consentedVersion,
            )
        }

        record?.revokedAtMillis != null -> {
            stringResource(
                R.string.consent_status_revoked,
                formatConsentDate(requireNotNull(record.revokedAtMillis)),
            )
        }

        else -> {
            stringResource(R.string.consent_status_none)
        }
    }

private fun formatConsentDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** 表示対象は提供済み機能の目的だけ。未提供機能は該当Issueの実装時に追加する。 */
private val AVAILABLE_PURPOSES = listOf(ConsentPurpose.SERIES_RELEASE_WATCH)

internal val ConsentPurpose.titleRes: Int
    get() =
        when (this) {
            ConsentPurpose.SERIES_RELEASE_WATCH -> R.string.consent_purpose_series_watch_title
            ConsentPurpose.LIBRARY_SYNC -> R.string.consent_purpose_sync_title
            ConsentPurpose.NATURAL_LANGUAGE_SEARCH -> R.string.consent_purpose_nl_search_title
            ConsentPurpose.AI_LIBRARIAN -> R.string.consent_purpose_ai_title
        }

internal val ConsentPurpose.purposeRes: Int
    get() =
        when (this) {
            ConsentPurpose.SERIES_RELEASE_WATCH -> R.string.consent_purpose_series_watch_purpose
            else -> R.string.consent_purpose_not_available
        }

internal val ConsentPurpose.destinationRes: Int
    get() =
        when (this) {
            ConsentPurpose.SERIES_RELEASE_WATCH -> R.string.consent_purpose_series_watch_destination
            else -> R.string.consent_purpose_not_available
        }

internal val ConsentPurpose.itemsRes: Int
    get() =
        when (this) {
            ConsentPurpose.SERIES_RELEASE_WATCH -> R.string.consent_purpose_series_watch_items
            else -> R.string.consent_purpose_not_available
        }

internal val ConsentPurpose.retentionRes: Int
    get() =
        when (this) {
            ConsentPurpose.SERIES_RELEASE_WATCH -> R.string.consent_purpose_series_watch_retention
            else -> R.string.consent_purpose_not_available
        }

internal val ConsentPurpose.thirdPartyRes: Int
    get() =
        when (this) {
            ConsentPurpose.SERIES_RELEASE_WATCH -> R.string.consent_purpose_series_watch_third_party
            else -> R.string.consent_purpose_not_available
        }

private const val MAX_PREVIEW_ITEMS = 10
