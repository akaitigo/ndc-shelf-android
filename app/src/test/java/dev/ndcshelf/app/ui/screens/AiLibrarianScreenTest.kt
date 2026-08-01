package dev.ndcshelf.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.AiLibrarianAnswerUi
import dev.ndcshelf.app.AiLibrarianPhase
import dev.ndcshelf.app.AiLibrarianUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswer
import dev.ndcshelf.app.domain.ai.AiLibrarianAnswerEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianBookReference
import dev.ndcshelf.app.domain.ai.AiLibrarianField
import dev.ndcshelf.app.domain.ai.AiLibrarianHistoryEntry
import dev.ndcshelf.app.domain.ai.AiLibrarianIntent
import dev.ndcshelf.app.domain.ai.AiLibrarianReason
import dev.ndcshelf.app.domain.ai.AiLibrarianRequest
import dev.ndcshelf.app.domain.ai.AiLibrarianRequestDraft
import dev.ndcshelf.app.domain.ai.aiTestBook
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiLibrarianScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun withoutConsentTheQuestionFormIsNotShown() {
        setContent(AiLibrarianUiState(consentGranted = false))

        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_consent_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_preview_button))
            .assertDoesNotExist()
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_provider_notice))
            .assertIsDisplayed()
    }

    @Test
    fun previewDialogShowsTargetsDestinationAndExcludedItemsBeforeSending() {
        var confirmed = false
        setContent(
            AiLibrarianUiState(
                consentGranted = true,
                pendingDraft = draft(),
                phase = AiLibrarianPhase.PREVIEW,
            ),
            onConfirmAsk = { confirmed = true },
        )

        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_preview_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText("・匿名サンプル図書A").assertExists()
        composeRule
            .onNodeWithText(
                context.getString(
                    R.string.ai_librarian_preview_destination,
                    context.getString(R.string.ai_librarian_destination_on_device),
                ),
            ).assertExists()
        assertFalse("確認前に送信してはならない", confirmed)

        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_preview_confirm))
            .performClick()

        assertEquals(true, confirmed)
    }

    @Test
    fun answerShowsReferencedBooksAndUncertaintyNotice() {
        setContent(
            AiLibrarianUiState(
                consentGranted = true,
                phase = AiLibrarianPhase.ANSWERED,
                answer =
                    AiLibrarianAnswerUi(
                        answer =
                            AiLibrarianAnswer(
                                intent = AiLibrarianIntent.PICK_NEXT,
                                entries =
                                    listOf(
                                        AiLibrarianAnswerEntry(
                                            label = "自然科学",
                                            reason = AiLibrarianReason.UNREAD_FIRST,
                                            refs = listOf("1"),
                                        ),
                                    ),
                            ),
                        references =
                            listOf(
                                AiLibrarianBookReference("1", "copy-1", "匿名サンプル図書A"),
                            ),
                        itemCount = 1,
                        includedFields = AiLibrarianField.DEFAULT_INCLUDED.toList(),
                    ),
            ),
        )

        scrollTo(context.getString(R.string.ai_librarian_answer_uncertainty))
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_answer_uncertainty))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_answer_references, 1))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_reason_unread_first))
            .assertExists()
        // 提案の内訳と「参照した本」の両方へ冊名を表示する。
        composeRule.onAllNodesWithText("・匿名サンプル図書A").assertCountEquals(2)
    }

    @Test
    fun inFlightQuestionCanBeCancelled() {
        var cancelled = false
        setContent(
            AiLibrarianUiState(consentGranted = true, phase = AiLibrarianPhase.ASKING),
            onCancelAsk = { cancelled = true },
        )

        scrollTo(context.getString(R.string.ai_librarian_cancel_button))
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_cancel_button))
            .performClick()

        assertEquals(true, cancelled)
    }

    @Test
    fun historyCanBeDeletedCompletely() {
        var cleared = false
        setContent(
            AiLibrarianUiState(
                consentGranted = true,
                history =
                    listOf(
                        AiLibrarianHistoryEntry(
                            id = "entry-1",
                            askedAtMillis = 1_753_000_000_000L,
                            question = "次に読む本を選んで",
                            intent = AiLibrarianIntent.PICK_NEXT,
                            itemCount = 2,
                            includedFields = AiLibrarianField.DEFAULT_INCLUDED.toList(),
                            referencedTitles = listOf("匿名サンプル図書A"),
                        ),
                    ),
            ),
            onClearHistory = { cleared = true },
        )

        scrollTo("次に読む本を選んで")
        composeRule.onNodeWithText("次に読む本を選んで").assertIsDisplayed()
        scrollTo(context.getString(R.string.ai_librarian_history_clear))
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_history_clear))
            .performClick()

        assertEquals(true, cleared)
    }

    @Test
    fun deletingHistoryIsDisabledWhenThereIsNothingToDelete() {
        setContent(AiLibrarianUiState(consentGranted = true))

        scrollTo(context.getString(R.string.ai_librarian_history_empty))
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_history_empty))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.ai_librarian_history_clear))
            .assertIsNotEnabled()
    }

    /** LazyColumnは画面外の要素を構成しないため、対象までスクロールしてから検証する。 */
    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun draft(): AiLibrarianRequestDraft {
        val result =
            dev.ndcshelf.app.domain.ai.AiLibrarianRequestBuilder
                .build(
                    question = "次に読む本を選んで",
                    books = listOf(aiTestBook(title = "匿名サンプル図書A")),
                    includedFields = AiLibrarianField.DEFAULT_INCLUDED,
                )
        return (result as dev.ndcshelf.app.domain.ai.AiLibrarianRequestResult.Prepared).draft
    }

    private fun setContent(
        state: AiLibrarianUiState,
        onConfirmAsk: () -> Unit = {},
        onCancelAsk: () -> Unit = {},
        onClearHistory: () -> Unit = {},
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                AiLibrarianScreen(
                    state = state,
                    onBack = {},
                    onQuestionChange = {},
                    onSelectScope = {},
                    onToggleBook = {},
                    onClearBookSelection = {},
                    onSelectTag = {},
                    onToggleField = {},
                    onResetFields = {},
                    onPreview = {},
                    onDismissPreview = {},
                    onConfirmAsk = onConfirmAsk,
                    onCancelAsk = onCancelAsk,
                    onDismissAnswer = {},
                    onGrantConsent = {},
                    onRevokeConsent = {},
                    onClearHistory = onClearHistory,
                    contentPadding = PaddingValues(),
                )
            }
        }
    }
}
