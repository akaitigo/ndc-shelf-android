package dev.ndcshelf.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.BookstoreUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.ScanFailure
import dev.ndcshelf.app.ScanUiState
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraPermissionCardTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

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

        composeRule.onNodeWithText(context.getString(R.string.camera_permission_allow)).performClick()
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

        composeRule.onNodeWithText(context.getString(R.string.camera_permission_permanent))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.camera_permission_settings)).performClick()
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

        composeRule.onNodeWithText(context.getString(R.string.scan_retry)).performClick()
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

        composeRule.onNodeWithText(context.getString(R.string.scan_retry)).performClick()
        assertEquals(1, restarts)
    }
}
