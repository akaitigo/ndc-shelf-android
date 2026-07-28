package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Rule
import org.junit.Test

class AppInfoScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overview_disclosesPrivacySourceBackupAndBuild() {
        setContent()

        composeRule.onNodeWithText("バージョン 0.1.2（3）・debug ビルド")
            .assertIsDisplayed()
        composeRule.onNodeWithText("プライバシーとデータ").assertIsDisplayed()
        scrollOverviewTo(hasText("外部サービスへの通信"))
        composeRule.onNodeWithText("外部サービスへの通信").assertIsDisplayed()
        scrollOverviewTo(hasText("バックアップとファイル"))
        composeRule.onNodeWithText("バックアップとファイル").assertIsDisplayed()
        scrollOverviewTo(
            hasText(
                "Androidのクラウドバックアップから全データを除外しています。",
                substring = true,
            ),
        )
        composeRule.onNodeWithText(
            "Androidのクラウドバックアップから全データを除外しています。",
            substring = true,
        ).assertIsDisplayed()
        scrollOverviewTo(hasText("国立国会図書館サーチ"))
        composeRule.onNodeWithText("国立国会図書館サーチ").assertIsDisplayed()
    }

    @Test
    fun appLicense_isBundledAndReadableOffline() {
        setContent()

        scrollOverviewTo(hasTestTag(APP_LICENSE_BUTTON_TAG))
        composeRule.onNodeWithTag(APP_LICENSE_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LICENSE_DETAIL_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Copyright 2026 NDC Shelf contributors")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Apache License\nVersion 2.0, January 2004", substring = true)
            .assertExists()
    }

    @Test
    fun generatedList_containsDirectAndTransitiveDependenciesWithLicenseText() {
        setContent()

        scrollOverviewTo(hasTestTag(OSS_LICENSE_BUTTON_TAG))
        composeRule.onNodeWithTag(OSS_LICENSE_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(OSS_LICENSE_LIST_TAG).assertIsDisplayed()

        val search = composeRule.onNodeWithText("名前、提供元、座標で検索")
        search.performTextInput("okio-jvm")
        composeRule.onNodeWithText("okio").assertIsDisplayed()
        search.performTextClearance()
        search.performTextInput("commons-csv")
        composeRule.onNodeWithText("Apache Commons CSV").performClick()
        composeRule.onNodeWithText("Maven座標: org.apache.commons:commons-csv")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Apache License 2.0").assertIsDisplayed()
    }

    private fun setContent() {
        composeRule.setContent {
            NdcShelfTheme {
                AppInfoScreen(
                    versionName = "0.1.2",
                    versionCode = 3,
                    buildType = "debug",
                    onOpenUrl = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    private fun scrollOverviewTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNodeWithTag(INFO_OVERVIEW_TAG).performScrollToNode(matcher)
    }
}
