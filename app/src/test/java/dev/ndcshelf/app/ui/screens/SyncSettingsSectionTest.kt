package dev.ndcshelf.app.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.R
import dev.ndcshelf.app.SyncSettingsViewModel
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncConfigurationStatus
import dev.ndcshelf.app.domain.sync.SyncDeviceInfo
import dev.ndcshelf.app.domain.sync.SyncFailure
import dev.ndcshelf.app.domain.sync.SyncFailureReason
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSettingsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun render(
        configuration: SyncConfigurationStatus = SyncConfigurationStatus(),
        devices: List<SyncDeviceInfo> = emptyList(),
        uiState: SyncSettingsViewModel.SyncUiState = SyncSettingsViewModel.SyncUiState(),
        consentGranted: Boolean = false,
        onGrantConsent: () -> Unit = {},
        onStartCreate: () -> Unit = {},
        onRevokeDevice: (String) -> Unit = {},
        onPurgeRemote: () -> Unit = {},
        onStopSync: () -> Unit = {},
        onSyncNow: () -> Unit = {},
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                SyncSettingsSection(
                    configuration = configuration,
                    devices = devices,
                    uiState = uiState,
                    consentGranted = consentGranted,
                    onGrantConsent = onGrantConsent,
                    onStartCreate = onStartCreate,
                    onStartJoin = {},
                    onSyncNow = onSyncNow,
                    onCompleteJoin = {},
                    onCreateInvite = {},
                    onRefreshJoinCandidates = {},
                    onApproveJoin = {},
                    onRevokeDevice = onRevokeDevice,
                    onPurgeRemote = onPurgeRemote,
                    onStopSync = onStopSync,
                    onDismissInvite = {},
                    onDismissReceipt = {},
                    onDismissFailure = {},
                )
            }
        }
    }

    @Test
    fun enablingSyncWithoutConsentShowsThePayloadDialogFirst() {
        var granted = false
        var created = false
        render(consentGranted = false, onGrantConsent = { granted = true }, onStartCreate = { created = true })

        composeRule.onNodeWithTag(SYNC_ENABLE_TAG).performClick()
        // 同意ダイアログを経由するまで有効化しない（既定OFF・fail-closed）。
        assertTrue(!granted && !created)
        composeRule
            .onNodeWithText(context.getString(R.string.consent_preview_title))
            .assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.consent_cancel_button)).performClick()
        assertTrue(!granted && !created)

        composeRule.onNodeWithTag(SYNC_ENABLE_TAG).performClick()
        composeRule.onNodeWithText(context.getString(R.string.consent_accept_button)).performClick()
        assertTrue(granted && created)
    }

    @Test
    fun activeSyncShowsDeviceListWithLastSyncAndRevokeConfirmation() {
        var revoked: String? = null
        render(
            configuration = SyncConfigurationStatus(configured = true, activated = true, epoch = 2),
            devices =
                listOf(
                    SyncDeviceInfo("self", "この端末", isSelf = true, revoked = false, lastSyncAtMillis = 1_000, addedAtGeneration = 1),
                    SyncDeviceInfo("peer", "書斎の端末", isSelf = false, revoked = false, lastSyncAtMillis = null, addedAtGeneration = 2),
                ),
            consentGranted = true,
            onRevokeDevice = { revoked = it },
        )

        composeRule.onNodeWithTag(SYNC_NOW_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SYNC_ADD_DEVICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("書斎の端末").assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.sync_status_never), substring = true)
            .assertExists()

        // 失効は確認ダイアログを経てから実行する。
        composeRule.onNodeWithTag("${SYNC_REVOKE_TAG_PREFIX}peer").performClick()
        assertNull(revoked)
        composeRule
            .onNodeWithText(context.getString(R.string.sync_revoke_confirm_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.import_cancel)).performClick()
        assertNull(revoked)

        composeRule.onNodeWithTag("${SYNC_REVOKE_TAG_PREFIX}peer").performClick()
        composeRule.onNodeWithTag(SYNC_REVOKE_CONFIRM_TAG).performClick()
        assertEquals("peer", revoked)
    }

    @Test
    fun purgeAndStopRequireExplicitConfirmation() {
        var purged = false
        var stopped = false
        render(
            configuration = SyncConfigurationStatus(configured = true, activated = true),
            consentGranted = true,
            onPurgeRemote = { purged = true },
            onStopSync = { stopped = true },
        )

        composeRule.onNodeWithTag(SYNC_PURGE_TAG).performClick()
        assertTrue(!purged)
        composeRule
            .onNodeWithText(context.getString(R.string.sync_purge_confirm_text))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SYNC_PURGE_CONFIRM_TAG).performClick()
        assertTrue(purged)

        composeRule.onNodeWithTag(SYNC_STOP_TAG).performClick()
        assertTrue(!stopped)
        composeRule
            .onNodeWithText(context.getString(R.string.sync_stop_confirm_text))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SYNC_STOP_CONFIRM_TAG).performClick()
        assertTrue(stopped)
    }

    @Test
    fun failuresAreShownWithoutExposingPersonalData() {
        render(
            configuration = SyncConfigurationStatus(configured = true, activated = true),
            consentGranted = true,
            uiState =
                SyncSettingsViewModel.SyncUiState(
                    lastFailure =
                        SyncFailure(SyncFailureReason.BACKEND, SyncBackendErrorKind.PERMISSION_LOST),
                ),
        )

        composeRule.onNodeWithTag(SYNC_ERROR_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.sync_error_permission_lost))
            .assertIsDisplayed()
    }

    @Test
    fun securityLockoutIsSurfacedToTheUser() {
        render(
            configuration =
                SyncConfigurationStatus(configured = true, activated = true, securityLockout = "tamper"),
            consentGranted = true,
        )

        composeRule
            .onNodeWithText(context.getString(R.string.sync_security_lockout))
            .assertIsDisplayed()
    }

    @Test
    fun pendingJoinShowsVerificationCodeAndCompleteAction() {
        render(
            configuration = SyncConfigurationStatus(configured = true, activated = false, joinPending = true),
            consentGranted = true,
            uiState = SyncSettingsViewModel.SyncUiState(joinVerificationCode = "123456"),
        )

        composeRule.onNodeWithTag(SYNC_JOIN_VERIFICATION_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.sync_verification_code, "123456"))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SYNC_COMPLETE_JOIN_TAG).assertIsDisplayed()
    }
}
