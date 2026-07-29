package dev.ndcshelf.app

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.ndcshelf.app.ui.screens.MANUAL_REGISTRATION_TITLE_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 実機・エミュレーター向けE2E: 手動登録 → 本棚表示 → Activity再生成後の
 * 状態維持を、実DB・実ナビゲーションで検証する。匿名データのみ使用。
 */
@RunWith(AndroidJUnit4::class)
class E2eManualRegistrationTest {
    // スキャンタブ初回表示の権限システムダイアログでテストが停止しないよう事前付与する。
    // 権限拒否時の代替経路（手入力）はCameraPermissionCardTestで検証済み。
    @get:Rule
    val grantCamera: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

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
            .onNodeWithText(text(R.string.manual_registration_open))
            .performScrollTo()
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
        composeRule.onNodeWithText(text(R.string.manual_registration_save)).performClick()
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
