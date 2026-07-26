package dev.ndcshelf.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ndcshelf.app.ScanSessionUiState
import dev.ndcshelf.app.domain.model.ScanAttempt
import dev.ndcshelf.app.domain.model.ScanAttemptOutcome
import dev.ndcshelf.app.domain.model.ScanSession
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScanSessionPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeCountAndHistoryAreShownAndUndoRequiresConfirmation() {
        var undoneAttempt: String? = null
        composeRule.setContent {
            NdcShelfTheme {
                ScanSessionPanel(
                    sessions = listOf(session()),
                    state = ScanSessionUiState.Idle,
                    onStart = {},
                    onFinish = {},
                    onUndoAttempt = { undoneAttempt = it },
                    onUndoSession = {},
                )
            }
        }

        composeRule.onNodeWithText("試行 2件・追加 1冊").assertIsDisplayed()
        composeRule.onNodeWithText("9784101010014").assertIsDisplayed()
        composeRule.onNodeWithText("取り消す").performClick()
        composeRule.onNodeWithText("追加を取り消しますか？").assertIsDisplayed()
        assertEquals(null, undoneAttempt)
        composeRule.onNodeWithText("確認して取り消す").performClick()
        assertEquals("attempt-added", undoneAttempt)
    }

    @Test
    fun dismissingUndoConfirmationDoesNotDelete() {
        var undoCount = 0
        composeRule.setContent {
            NdcShelfTheme {
                ScanSessionPanel(
                    sessions = listOf(session()),
                    state = ScanSessionUiState.Idle,
                    onStart = {},
                    onFinish = {},
                    onUndoAttempt = { undoCount += 1 },
                    onUndoSession = { undoCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("取り消す").performClick()
        composeRule.onNodeWithText("キャンセル").performClick()

        assertEquals(0, undoCount)
    }

    private fun session() = ScanSession(
        id = "session",
        startedAt = 1,
        endedAt = null,
        attempts = listOf(
            ScanAttempt(
                id = "attempt-added",
                sessionId = "session",
                isbn = "9784101010014",
                outcome = ScanAttemptOutcome.ADDED,
                copyId = "copy",
                attemptedAt = 2,
                undoneAt = null,
            ),
            ScanAttempt(
                id = "attempt-duplicate",
                sessionId = "session",
                isbn = "9784003101018",
                outcome = ScanAttemptOutcome.DUPLICATE,
                copyId = null,
                attemptedAt = 3,
                undoneAt = null,
            ),
        ),
    )
}
