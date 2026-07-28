package dev.ndcshelf.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ndcshelf.app.BookstoreUiState
import dev.ndcshelf.app.ScanFailure
import dev.ndcshelf.app.ScanUiState
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraPermissionCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ordinaryDenialCanRequestAgain() {
        var requested = 0
        composeRule.setContent {
            NdcShelfTheme {
                CameraPermissionCard(
                    permanentlyDenied = false,
                    onRequestPermission = { requested += 1 },
                    onOpenSettings = {},
                )
            }
        }

        composeRule.onNodeWithText("カメラを許可").performClick()
        assertEquals(1, requested)
    }

    @Test
    fun permanentDenialExplainsManualEntryAndOpensSettings() {
        var settingsOpened = 0
        composeRule.setContent {
            NdcShelfTheme {
                CameraPermissionCard(
                    permanentlyDenied = true,
                    onRequestPermission = {},
                    onOpenSettings = { settingsOpened += 1 },
                )
            }
        }

        composeRule.onNodeWithText("手入力はそのまま利用できます。", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Androidの設定を開く").performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun cameraConflictCanRestartLibraryCamera() {
        var restarts = 0
        composeRule.setContent {
            NdcShelfTheme {
                ScanResultCard(
                    state = ScanUiState.Error(ScanFailure.CAMERA),
                    onRetry = {},
                    onCameraRetry = { restarts += 1 },
                    onClear = {},
                    onAddDuplicateCopy = {},
                    onOpenManualRegistration = {},
                )
            }
        }

        composeRule.onNodeWithText("再試行").performClick()
        assertEquals(1, restarts)
    }

    @Test
    fun cameraConflictCanRestartBookstoreCamera() {
        var restarts = 0
        composeRule.setContent {
            NdcShelfTheme {
                BookstoreResultCard(
                    state = BookstoreUiState.Error(ScanFailure.CAMERA),
                    onRetry = {},
                    onCameraRetry = { restarts += 1 },
                    onClear = {},
                    onChangeState = {},
                )
            }
        }

        composeRule.onNodeWithText("再試行").performClick()
        assertEquals(1, restarts)
    }
}
