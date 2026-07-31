package dev.ndcshelf.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
                    payloadPreviewItems =
                        mapOf(
                            ConsentPurpose.SERIES_RELEASE_WATCH to listOf("年代記", "銀河の歴史"),
                        ),
                    onGrant = { granted = it },
                    onRevoke = {},
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule
            .onAllNodes(hasText(context.getString(R.string.consent_status_none)))[0]
            .assertIsDisplayed()
        grantButtonFor(ConsentPurpose.SERIES_RELEASE_WATCH).performClick()

        composeRule.onNodeWithText("・年代記").assertExists()
        composeRule.onNodeWithText("・銀河の歴史").assertExists()
        assertNull(granted)

        composeRule.onNodeWithText(context.getString(R.string.consent_cancel_button)).performClick()
        assertNull(granted)

        grantButtonFor(ConsentPurpose.SERIES_RELEASE_WATCH).performClick()
        composeRule.onNodeWithText(context.getString(R.string.consent_accept_button)).performClick()
        assertEquals(ConsentPurpose.SERIES_RELEASE_WATCH, granted)
    }

    @Test
    fun aiLibrarianPurposeIsListedWithItsOwnDestinationAndItems() {
        var granted: ConsentPurpose? = null
        composeRule.setContent {
            NdcShelfTheme {
                ConsentScreen(
                    consents = emptyMap(),
                    payloadPreviewItems = emptyMap(),
                    onGrant = { granted = it },
                    onRevoke = {},
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText(context.getString(R.string.consent_purpose_ai_title)))
        composeRule
            .onNodeWithText(context.getString(R.string.consent_purpose_ai_title))
            .assertIsDisplayed()

        grantButtonFor(ConsentPurpose.AI_LIBRARIAN).performClick()

        composeRule
            .onNodeWithText(context.getString(R.string.consent_preview_empty_ai))
            .assertExists()
        assertNull(granted)

        composeRule.onNodeWithText(context.getString(R.string.consent_accept_button)).performClick()
        assertEquals(ConsentPurpose.AI_LIBRARIAN, granted)
    }

    /** 同じラベルのボタンが目的ごとに並ぶため、表示順で対象を選ぶ。 */
    private fun grantButtonFor(purpose: ConsentPurpose): SemanticsNodeInteraction {
        val index = listOf(ConsentPurpose.SERIES_RELEASE_WATCH, ConsentPurpose.AI_LIBRARIAN).indexOf(purpose)
        return composeRule.onAllNodes(hasText(context.getString(R.string.consent_grant_button)))[index]
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
                    payloadPreviewItems = emptyMap(),
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
