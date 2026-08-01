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
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.R
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Rule
import org.junit.Test

class AppInfoScreenTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overview_disclosesPrivacySourceBackupAndBuild() {
        setContent()

        composeRule
            .onNodeWithText(context.getString(R.string.info_version_value, "0.1.2", 3, "debug"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.info_privacy_title)).assertIsDisplayed()
        scrollOverviewTo(hasText(context.getString(R.string.info_privacy_network_title)))
        composeRule.onNodeWithText(context.getString(R.string.info_privacy_network_title)).assertIsDisplayed()
        scrollOverviewTo(hasText(context.getString(R.string.info_privacy_backup_title)))
        composeRule.onNodeWithText(context.getString(R.string.info_privacy_backup_title)).assertIsDisplayed()
        val backupBody = context.getString(R.string.info_privacy_backup_body)
        scrollOverviewTo(hasText(backupBody))
        composeRule.onNodeWithText(backupBody).assertIsDisplayed()
        scrollOverviewTo(hasText(context.getString(R.string.info_source_ndl_title)))
        composeRule.onNodeWithText(context.getString(R.string.info_source_ndl_title)).assertIsDisplayed()
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

        val search = composeRule.onNodeWithText(context.getString(R.string.info_oss_search))
        search.performTextInput("okio-jvm")
        composeRule.onNodeWithText("okio").assertIsDisplayed()
        search.performTextClearance()
        search.performTextInput("commons-csv")
        composeRule.onNodeWithText("Apache Commons CSV").performClick()
        composeRule
            .onNodeWithText(
                context.getString(R.string.info_oss_coordinate, "org.apache.commons:commons-csv"),
            )
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
