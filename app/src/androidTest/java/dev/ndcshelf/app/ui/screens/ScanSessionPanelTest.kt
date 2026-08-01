package dev.ndcshelf.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import dev.ndcshelf.app.R
import dev.ndcshelf.app.ScanSessionUiState
import dev.ndcshelf.app.domain.model.ScanAttempt
import dev.ndcshelf.app.domain.model.ScanAttemptOutcome
import dev.ndcshelf.app.domain.model.ScanSession
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.hamcrest.Matchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScanSessionPanelTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun enableChecks() {
        // 操作を伴う既存テストでもAccessibility Test Frameworkの検査を走らせる。
        // 抑制方針は AccessibilityChecksTest と docs/ACCESSIBILITY_AUDIT.md を参照。
        composeRule.enableAccessibilityChecks(
            AccessibilityValidator()
                .setThrowExceptionFor(AccessibilityCheckResult.AccessibilityCheckResultType.ERROR)
                .setSuppressingResultMatcher(
                    Matchers.anyOf(
                        AccessibilityCheckResultUtils.matchesCheck(TextContrastCheck::class.java),
                        AccessibilityCheckResultUtils.matchesCheck(ImageContrastCheck::class.java),
                    ),
                ),
        )
    }

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

        composeRule
            .onNodeWithText(context.getString(R.string.scan_session_active_count, 2, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithText("9784101010014").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.scan_session_undo_one)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.scan_session_undo_title)).assertIsDisplayed()
        assertEquals(null, undoneAttempt)
        composeRule.onNodeWithText(context.getString(R.string.scan_session_undo_confirm)).performClick()
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

        composeRule.onNodeWithText(context.getString(R.string.scan_session_undo_one)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.import_cancel)).performClick()

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
