package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import dev.ndcshelf.app.DatabaseBackupUiState
import dev.ndcshelf.app.LibraryImportUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.hamcrest.Matchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DataManagementScreenTest {
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
    fun emptyLibrary_disablesOnlyOperationsThatNeedCurrentData() {
        setContent(bookCount = 0)

        val emptyReason = context.getString(R.string.data_management_empty_reason)
        assertButton(EXPORT_JSON_TAG, enabled = false, reason = emptyReason)
        assertButton(EXPORT_CSV_TAG, enabled = false, reason = emptyReason)
        assertButton(BACKUP_TAG, enabled = false, reason = emptyReason)
        assertButton(IMPORT_JSON_TAG, enabled = true)
        assertButton(IMPORT_CSV_TAG, enabled = true)
        assertButton(RESTORE_TAG, enabled = true)
    }

    @Test
    fun destructiveRestore_isSeparatedAndExplainsReplacement() {
        setContent(bookCount = 3)

        composeRule.onNodeWithTag(DATA_LIST_TAG)
            .performScrollToNode(hasText(context.getString(R.string.data_management_destructive_section)))
        composeRule.onNodeWithText(context.getString(R.string.data_management_destructive_section)).assertIsDisplayed()
        composeRule.onNodeWithTag(DATA_LIST_TAG).performScrollToNode(
            hasText(
                context.getString(R.string.data_management_restore_description),
            ),
        )
        composeRule.onNodeWithText(
            context.getString(R.string.data_management_restore_description),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(DATA_LIST_TAG).performScrollToNode(hasTestTag(RESTORE_TAG))
        composeRule.onNodeWithTag(RESTORE_TAG).assertIsEnabled()
    }

    @Test
    fun exportInProgress_blocksOtherOperationsAndShowsProgress() {
        setContent(bookCount = 3, exportInProgress = true)

        composeRule.onNodeWithText(context.getString(R.string.data_management_exporting)).assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_JSON_TAG, useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun syncStatus_defaultsToOfflineWithoutShowingLibraryData() {
        setContent(bookCount = 3)

        composeRule.onNodeWithTag(DATA_LIST_TAG).performScrollToNode(hasTestTag(SYNC_STATUS_TAG))
        composeRule.onNodeWithTag(SYNC_STATUS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sync_status_off)).assertIsDisplayed()
    }

    private fun setContent(
        bookCount: Int,
        exportInProgress: Boolean = false,
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                DataManagementScreen(
                    bookCount = bookCount,
                    exportInProgress = exportInProgress,
                    importState = LibraryImportUiState.Idle,
                    databaseBackupState = DatabaseBackupUiState.Idle,
                    onExportJson = {},
                    onExportCsv = {},
                    onImportJson = {},
                    onImportCsv = {},
                    onCreateDatabaseBackup = {},
                    onSelectDatabaseBackup = {},
                    onSelectImportPolicy = {},
                    onConfirmImport = {},
                    onDismissImport = {},
                    onConfirmDatabaseRestore = {},
                    onDismissDatabaseBackup = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
    }

    private fun assertButton(tag: String, enabled: Boolean, reason: String? = null) {
        composeRule.onNodeWithTag(DATA_LIST_TAG).performScrollToNode(hasTestTag(tag))
        val node = composeRule.onNodeWithTag(tag)
        if (enabled) {
            node.assertIsEnabled()
        } else {
            node.assertIsNotEnabled()
            val reasonNode = composeRule.onNodeWithTag("${tag}_reason").assertIsDisplayed()
            if (reason != null) reasonNode.assertTextEquals(reason)
        }
    }
}
