package dev.ndcshelf.app

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import dev.ndcshelf.app.ui.screens.MANUAL_REGISTRATION_TITLE_TAG
import dev.ndcshelf.app.ui.screens.SCAN_LIST_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JVM(Robolectric)でのCI層E2E: 実Application・実Room・実ナビゲーションで
 * 手動登録 → 本棚表示 → Activity再生成後の状態維持を検証する。
 * カメラ権限は未付与のままとし、権限カード表示下でも手動登録経路が
 * 使えること（権限任意の受け入れ条件）を同時に確認する。匿名データのみ使用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class E2eManualRegistrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun text(resId: Int): String = composeRule.activity.getString(resId)

    @Test
    fun manualRegistrationShowsInLibraryAndSurvivesActivityRecreation() {
        val bookTitle = "E2E匿名手動登録の本"
        composeRule.waitForIdle()

        // 初回起動時のオンボーディングはスキップできる
        val skipNodes = composeRule.onAllNodesWithText(text(R.string.onboarding_skip))
        if (skipNodes.fetchSemanticsNodes().isNotEmpty()) {
            skipNodes.onFirst().performClick()
            composeRule.waitForIdle()
        }

        // スキャンタブ → カメラ権限なしでも手動登録へ進める
        composeRule.onNodeWithText(text(R.string.navigation_scan)).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(SCAN_LIST_TAG)
            .performScrollToNode(hasText(text(R.string.manual_registration_open)))
        composeRule
            .onNodeWithText(text(R.string.manual_registration_open))
            .performClick()
        try {
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule
                    .onAllNodesWithText(text(R.string.manual_registration_title))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            composeRule.onRoot().printToLog("E2E_DIAG")
            throw timeout
        }

        // タイトルだけで登録できる
        composeRule
            .onNodeWithTag(MANUAL_REGISTRATION_TITLE_TAG)
            .performTextInput(bookTitle)
        composeRule
            .onNode(
                hasText(text(R.string.manual_registration_save))
                    .and(hasAnyAncestor(isDialog())),
            ).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText(text(R.string.import_close))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(text(R.string.import_close)).performClick()

        // 本棚タブへ移動して登録済みであることを確認する
        composeRule.onNodeWithText(text(R.string.navigation_library)).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(bookTitle).fetchSemanticsNodes().isNotEmpty()
        }

        // 回転・テーマ変更相当のActivity再生成後も表示が維持される
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(bookTitle).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
