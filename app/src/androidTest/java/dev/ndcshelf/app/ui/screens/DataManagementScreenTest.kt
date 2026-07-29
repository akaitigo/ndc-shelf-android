package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.ndcshelf.app.DatabaseBackupUiState
import dev.ndcshelf.app.LibraryImportUiState
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Rule
import org.junit.Test

class DataManagementScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLibrary_disablesOnlyOperationsThatNeedCurrentData() {
        setContent(bookCount = 0)

        val emptyReason = "本棚が空のため書き出すデータがありません"
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
            .performScrollToNode(hasText("現在のデータを置き換える操作"))
        composeRule.onNodeWithText("現在のデータを置き換える操作").assertIsDisplayed()
        composeRule.onNodeWithTag(DATA_LIST_TAG).performScrollToNode(
            hasText(
                "現在の全データをバックアップ内容で置き換えます。実行直前の状態はアプリ内へ自動退避します。",
            ),
        )
        composeRule.onNodeWithText(
            "現在の全データをバックアップ内容で置き換えます。実行直前の状態はアプリ内へ自動退避します。",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(DATA_LIST_TAG).performScrollToNode(hasTestTag(RESTORE_TAG))
        composeRule.onNodeWithTag(RESTORE_TAG).assertIsEnabled()
    }

    @Test
    fun exportInProgress_blocksOtherOperationsAndShowsProgress() {
        setContent(bookCount = 3, exportInProgress = true)

        composeRule.onNodeWithText("蔵書ファイルを書き出しています").assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_JSON_TAG, useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun syncStatus_defaultsToOfflineWithoutShowingLibraryData() {
        setContent(bookCount = 3)

        composeRule.onNodeWithTag(DATA_LIST_TAG).performScrollToNode(hasTestTag(SYNC_STATUS_TAG))
        composeRule.onNodeWithTag(SYNC_STATUS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("同期はオフです。蔵書は端末内だけで利用できます。").assertIsDisplayed()
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
