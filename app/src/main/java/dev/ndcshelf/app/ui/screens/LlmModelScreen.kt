package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.LlmModelFailure
import dev.ndcshelf.app.LlmModelUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.ai.llm.LlmUnsupportedReason
import java.util.Locale

/**
 * 端末内LLMモデルの管理画面（docs/adr/0009-on-device-llm-librarian.md）。
 *
 * 取得は利用者の明示操作でだけ始まり、[ConsentPurpose.MODEL_DOWNLOAD]へ同意していない
 * 場合は取得ボタンを提供しない。非対応端末では取得導線そのものを表示しない。
 */
@Composable
fun LlmModelScreen(
    state: LlmModelUiState,
    onBack: () -> Unit,
    onGrantConsent: () -> Unit,
    onRevokeConsent: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onVerify: () -> Unit,
    onDeleteAll: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.llm_model_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.llm_model_description),
            style = MaterialTheme.typography.bodyMedium,
        )

        val model = state.model
        if (model == null) {
            LlmModelCard {
                Text(
                    text = stringResource(R.string.llm_model_none_available),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LlmModelCard {
                Text(text = model.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.llm_model_license, model.licenseSpdxId),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.llm_model_size, formatMegabytes(model.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.llm_model_requirements, model.minSdkInt),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.llm_model_japanese_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
                model.knownLimitations.forEach { limitation ->
                    Text(text = "・$limitation", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!state.supported) {
            LlmModelCard {
                Text(
                    text = stringResource(R.string.llm_model_unsupported_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.unsupportedReasons.forEach { reason ->
                    Text(text = "・" + stringResource(reason.messageRes), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = stringResource(R.string.llm_model_unsupported_fallback),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LlmModelCard {
                Text(
                    text = stringResource(R.string.llm_model_consent_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.llm_model_consent_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.downloadConsentGranted) {
                    TextButton(onClick = onRevokeConsent) {
                        Text(stringResource(R.string.llm_model_consent_revoke))
                    }
                } else {
                    OutlinedButton(onClick = onGrantConsent) {
                        Text(stringResource(R.string.llm_model_consent_grant))
                    }
                }
            }

            LlmModelCard {
                Text(
                    text = stringResource(R.string.llm_model_status_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        if (state.installed) {
                            stringResource(
                                R.string.llm_model_status_installed,
                                formatMegabytes(state.installedSizeBytes),
                            )
                        } else {
                            stringResource(R.string.llm_model_status_not_installed)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (state.installing) {
                    val percent = (state.progressFraction * 100).toInt()
                    LinearProgressIndicator(
                        progress = { state.progressFraction },
                        modifier =
                            Modifier.fillMaxWidth().clearAndSetSemantics {
                                contentDescription = "$percent%"
                            },
                    )
                    Text(
                        text = stringResource(R.string.llm_model_downloading, percent),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(R.string.llm_model_cancel))
                    }
                } else {
                    if (!state.installed && state.downloadConsentGranted) {
                        OutlinedButton(onClick = onStartDownload) {
                            Text(stringResource(R.string.llm_model_download))
                        }
                    }
                    if (state.installed) {
                        OutlinedButton(onClick = onVerify) {
                            Text(stringResource(R.string.llm_model_verify))
                        }
                        TextButton(onClick = onDeleteAll) {
                            Text(stringResource(R.string.llm_model_delete))
                        }
                    }
                }
            }
        }

        state.failure?.let { failure ->
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = stringResource(failure.messageRes), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismissFailure) {
                        Text(stringResource(R.string.llm_model_dismiss))
                    }
                }
            }
        }

        TextButton(onClick = onBack) { Text(stringResource(R.string.llm_model_back)) }
    }
}

@Composable
private fun LlmModelCard(content: @Composable () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

private fun formatMegabytes(bytes: Long): String = String.format(Locale.US, "%.0f", bytes.toDouble() / (1024.0 * 1024.0))

internal val LlmUnsupportedReason.messageRes: Int
    get() =
        when (this) {
            LlmUnsupportedReason.DISABLED -> R.string.llm_model_unsupported_disabled
            LlmUnsupportedReason.NO_MODEL_AVAILABLE -> R.string.llm_model_unsupported_no_model
            LlmUnsupportedReason.SDK_TOO_OLD -> R.string.llm_model_unsupported_sdk
            LlmUnsupportedReason.ABI_UNSUPPORTED -> R.string.llm_model_unsupported_abi
            LlmUnsupportedReason.INSUFFICIENT_RAM -> R.string.llm_model_unsupported_ram
            LlmUnsupportedReason.LOW_RAM_DEVICE -> R.string.llm_model_unsupported_low_ram
            LlmUnsupportedReason.INSUFFICIENT_STORAGE -> R.string.llm_model_unsupported_storage
            LlmUnsupportedReason.RUNTIME_UNAVAILABLE -> R.string.llm_model_unsupported_runtime
        }

internal val LlmModelFailure.messageRes: Int
    get() =
        when (this) {
            LlmModelFailure.NOT_CONSENTED -> R.string.llm_model_failure_not_consented
            LlmModelFailure.DEVICE_UNSUPPORTED -> R.string.llm_model_failure_device
            LlmModelFailure.TRANSPORT -> R.string.llm_model_failure_transport
            LlmModelFailure.SIZE_MISMATCH -> R.string.llm_model_failure_size
            LlmModelFailure.CHECKSUM_MISMATCH -> R.string.llm_model_failure_checksum
            LlmModelFailure.STORAGE_ERROR -> R.string.llm_model_failure_storage
            LlmModelFailure.CANCELLED -> R.string.llm_model_failure_cancelled
        }
