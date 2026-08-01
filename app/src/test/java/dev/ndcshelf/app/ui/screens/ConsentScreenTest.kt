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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        seriesGrantButton().performClick()

        composeRule
            .onNodeWithText(context.getString(R.string.consent_bullet_item, "年代記"))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.consent_bullet_item, "銀河の歴史"))
            .assertExists()
        assertNull(granted)

        composeRule.onNodeWithText(context.getString(R.string.consent_cancel_button)).performClick()
        assertNull(granted)

        seriesGrantButton().performClick()
        composeRule.onNodeWithText(context.getString(R.string.consent_accept_button)).performClick()
        assertEquals(ConsentPurpose.SERIES_RELEASE_WATCH, granted)
    }

    @Test
    fun aiLibrarianPurposeIsListedWithItsOwnDestinationAndItems() {
        composeRule.setContent {
            NdcShelfTheme {
                ConsentScreen(
                    consents = emptyMap(),
                    payloadPreviewItems = emptyMap(),
                    onGrant = {},
                    onRevoke = {},
                    contentPadding = PaddingValues(),
                )
            }
        }

        scrollTo(context.getString(R.string.consent_purpose_ai_title))
        composeRule
            .onNodeWithText(context.getString(R.string.consent_purpose_ai_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.consent_purpose_ai_destination))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.consent_purpose_ai_items))
            .assertExists()
    }

    /**
     * AI司書は送信対象が質問ごとに決まるため、同意画面のプレビューは
     * 「質問の直前に必ず確認できる」ことを説明する。同意は明示操作でのみ成立する。
     */
    @Test
    fun aiLibrarianPayloadDialogExplainsPerQuestionSelection() {
        var accepted = false
        var dismissed = false
        composeRule.setContent {
            NdcShelfTheme {
                ConsentPayloadDialog(
                    purpose = ConsentPurpose.AI_LIBRARIAN,
                    payloadItems = emptyList(),
                    onAccept = { accepted = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.consent_preview_empty_ai))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.consent_purpose_ai_destination))
            .assertExists()
        assertFalse("表示しただけでは同意しない", accepted)

        composeRule.onNodeWithText(context.getString(R.string.consent_cancel_button)).performClick()
        assertTrue(dismissed)
        assertFalse(accepted)

        composeRule.onNodeWithText(context.getString(R.string.consent_accept_button)).performClick()
        assertTrue(accepted)
    }

    /** シリーズ新刊は一覧の先頭カードなので、スクロールなしで先頭の同意ボタンを選べる。 */
    private fun seriesGrantButton(): SemanticsNodeInteraction =
        composeRule.onAllNodes(hasText(context.getString(R.string.consent_grant_button)))[0]

    /** LazyColumnは画面外の要素を構成しないため、対象までスクロールしてから検証する。 */
    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
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
