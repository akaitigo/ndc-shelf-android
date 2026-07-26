package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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

        composeRule.onNodeWithTag(EXPORT_JSON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(EXPORT_CSV_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(BACKUP_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(IMPORT_JSON_TAG).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(IMPORT_CSV_TAG).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(RESTORE_TAG).performScrollTo().assertIsEnabled()
        composeRule.onAllNodesWithText("本棚が空のため書き出すデータがありません")
            .assertCountEquals(3)
    }

    @Test
    fun destructiveRestore_isSeparatedAndExplainsReplacement() {
        setContent(bookCount = 3)

        composeRule.onNodeWithText("現在のデータを置き換える操作")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "現在の全データをバックアップ内容で置き換えます。実行直前の状態はアプリ内へ自動退避します。",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RESTORE_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun exportInProgress_blocksOtherOperationsAndShowsProgress() {
        setContent(bookCount = 3, exportInProgress = true)

        composeRule.onNodeWithText("蔵書ファイルを書き出しています").assertIsDisplayed()
        composeRule.onNodeWithTag(EXPORT_JSON_TAG, useUnmergedTree = true).assertIsNotEnabled()
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
}
