package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.R

/**
 * 初回オンボーディング。いつでもスキップでき、将来の任意機能（同期・AI等）への
 * 同意はここで求めない（各機能の利用開始時に個別説明する）。
 */
@Composable
fun OnboardingScreen(
    onStartScan: () -> Unit,
    onManualEntry: () -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    contentPadding: PaddingValues,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_indicator, page + 1, PAGE_COUNT),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
        LinearProgressIndicator(
            progress = { (page + 1).toFloat() / PAGE_COUNT },
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                0 -> {
                    OnboardingPage(
                        titleRes = R.string.onboarding_welcome_title,
                        bodyRes = R.string.onboarding_welcome_body,
                    )
                }

                1 -> {
                    OnboardingPage(
                        titleRes = R.string.onboarding_camera_title,
                        bodyRes = R.string.onboarding_camera_body,
                    )
                }

                2 -> {
                    OnboardingPage(
                        titleRes = R.string.onboarding_privacy_title,
                        bodyRes = R.string.onboarding_privacy_body,
                    )
                }

                else -> {
                    OnboardingPage(
                        titleRes = R.string.onboarding_start_title,
                        bodyRes = R.string.onboarding_start_body,
                    )
                    Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_action_scan))
                    }
                    OutlinedButton(onClick = onManualEntry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_action_manual))
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_action_import))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (page > 0) {
                OutlinedButton(onClick = { page -= 1 }) {
                    Text(stringResource(R.string.onboarding_back))
                }
            } else {
                // 空のTextは無意味なフォーカス停止点になるためSpacerで場所を確保する。
                Spacer(Modifier.padding(8.dp))
            }
            if (page < PAGE_COUNT - 1) {
                Button(onClick = { page += 1 }) {
                    Text(stringResource(R.string.onboarding_next))
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(
    titleRes: Int,
    bodyRes: Int,
) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Card {
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private const val PAGE_COUNT = 4
