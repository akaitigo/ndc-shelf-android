package dev.ndcshelf.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun grantFlowShowsRealPayloadBeforeConsentAndNeverGrantsOnCancel() {
        var granted: ConsentPurpose? = null
        composeRule.setContent {
            NdcShelfTheme {
                ConsentScreen(
                    consents = emptyMap(),
                    payloadPreviewItems = listOf("年代記", "銀河の歴史"),
                    onGrant = { granted = it },
                    onRevoke = {},
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.consent_status_none))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.consent_grant_button)).performClick()

        composeRule.onNodeWithText("・年代記").assertExists()
        composeRule.onNodeWithText("・銀河の歴史").assertExists()
        assertNull(granted)

        composeRule.onNodeWithText(context.getString(R.string.consent_cancel_button)).performClick()
        assertNull(granted)

        composeRule.onNodeWithText(context.getString(R.string.consent_grant_button)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.consent_accept_button)).performClick()
        assertEquals(ConsentPurpose.SERIES_RELEASE_WATCH, granted)
    }

    @Test
    fun revokeRequiresExplicitConfirmation() {
        var revoked: ConsentPurpose? = null
        composeRule.setContent {
            NdcShelfTheme {
                ConsentScreen(
                    consents =
                        mapOf(
                            ConsentPurpose.SERIES_RELEASE_WATCH to
                                ConsentRecord(
                                    purpose = ConsentPurpose.SERIES_RELEASE_WATCH,
                                    consentedVersion = ConsentPurpose.SERIES_RELEASE_WATCH.policyVersion,
                                    grantedAtMillis = 1_700_000_000_000L,
                                    revokedAtMillis = null,
                                ),
                        ),
                    payloadPreviewItems = emptyList(),
                    onGrant = {},
                    onRevoke = { revoked = it },
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.consent_revoke_button)).performClick()
        assertNull(revoked)
        composeRule
            .onNodeWithText(context.getString(R.string.consent_revoked_notice))
            .assertIsDisplayed()

        composeRule
            .onAllNodes(
                androidx.compose.ui.test
                    .hasText(context.getString(R.string.consent_revoke_button)),
            )[1]
            .performClick()
        assertEquals(ConsentPurpose.SERIES_RELEASE_WATCH, revoked)
    }
}
