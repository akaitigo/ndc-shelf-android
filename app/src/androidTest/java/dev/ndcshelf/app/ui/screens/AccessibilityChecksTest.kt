package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.DatabaseBackupUiState
import dev.ndcshelf.app.LibraryImportUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.ScanSessionUiState
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.ScanAttempt
import dev.ndcshelf.app.domain.model.ScanAttemptOutcome
import dev.ndcshelf.app.domain.model.ScanSession
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.hamcrest.Matchers
import org.junit.Rule
import org.junit.Test

/**
 * 主要画面へAccessibility Test Framework（Accessibility Scanner相当）の自動チェックを
 * 適用する回帰テスト。ラベル欠落・48dp未満のタップ領域・重複クリック領域などを検出する。
 *
 * 検出範囲と抑制理由は docs/ACCESSIBILITY_AUDIT.md の「自動チェックの範囲」節に記録する。
 * 実機TalkBack・Switch Access・拡大・モーション低減は本テストの対象外で、
 * 同ドキュメントの「実機ゲート」に従いリリースゲートで実施する。
 *
 * fixtureは匿名データだけを使い、実在ISBN・氏名・棚位置を含めない。
 */
class AccessibilityChecksTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun libraryScreenPassesAccessibilityChecks() {
        composeRule.setContent {
            NdcShelfTheme {
                LibraryFixture()
            }
        }
        runChecks()
    }

    @Test
    fun dataManagementScreenPassesAccessibilityChecks() {
        composeRule.setContent {
            NdcShelfTheme {
                DataManagementFixture()
            }
        }
        runChecks()
    }

    @Test
    fun scanSessionPanelPassesAccessibilityChecks() {
        composeRule.setContent {
            NdcShelfTheme {
                ScanSessionFixture()
            }
        }
        runChecks()
    }

    /**
     * ERROR判定のみでテストを失敗させる。
     *
     * コントラスト系（[TextContrastCheck] / [ImageContrastCheck]）は、Android 12以降の
     * 動的配色で実際の描画色が端末ごとに変わり、エミュレーターの既定配色での判定が
     * 実機を代表しないため抑制する。コントラストは docs/ACCESSIBILITY_AUDIT.md の
     * コントラスト欄と実機ゲート（拡大・TalkBack実走）で担保する。
     */
    private fun runChecks() {
        val validator =
            AccessibilityValidator()
                .setThrowExceptionFor(AccessibilityCheckResult.AccessibilityCheckResultType.ERROR)
                .setSuppressingResultMatcher(
                    Matchers.anyOf(
                        AccessibilityCheckResultUtils.matchesCheck(TextContrastCheck::class.java),
                        AccessibilityCheckResultUtils.matchesCheck(ImageContrastCheck::class.java),
                    ),
                )
        composeRule.enableAccessibilityChecks(validator)
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }
}

@Composable
private fun LibraryFixture() {
    LibraryScreen(
        books = anonymousBooks(),
        libraryStats = LibraryStats(totalCount = 2, classifiedCount = 1, readingCount = 1),
        onSaveBook = { _, _ -> },
        onDeleteBook = {},
        bookEditState = BookEditUiState.Idle,
        onClearBookEditState = {},
        bookDeleteState = BookDeleteUiState.Idle,
        onClearBookDeleteState = {},
        locations = LocationTree(),
        locationMutationState = LocationMutationUiState.Idle,
        onAddLocation = { _, _, _ -> },
        onRenameLocation = { _, _, _ -> },
        onMoveLocation = { _, _, _ -> },
        onDeleteLocation = { _, _, _, _ -> },
        onClearLocationState = {},
        contentPadding = PaddingValues(),
    )
}

@Composable
private fun DataManagementFixture() {
    DataManagementScreen(
        bookCount = 3,
        exportInProgress = false,
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

@Composable
private fun ScanSessionFixture() {
    ScanSessionPanel(
        sessions = listOf(anonymousSession()),
        state = ScanSessionUiState.Idle,
        onStart = {},
        onFinish = {},
        onUndoAttempt = {},
        onUndoSession = {},
    )
}

private fun anonymousSession() =
    ScanSession(
        id = "session",
        startedAt = 1,
        endedAt = null,
        attempts =
            listOf(
                ScanAttempt(
                    id = "attempt-added",
                    sessionId = "session",
                    isbn = "9784101010014",
                    outcome = ScanAttemptOutcome.ADDED,
                    copyId = "copy-1",
                    attemptedAt = 2,
                    undoneAt = null,
                ),
            ),
    )

private fun anonymousBooks() =
    listOf(
        anonymousBook("copy-1", "匿名サンプル図書A", "007.6", ReadingStatus.READING),
        anonymousBook("copy-2", "匿名サンプル図書B", null, ReadingStatus.UNREAD),
    )

private fun anonymousBook(
    copyId: String,
    title: String,
    ndcCode: String?,
    status: ReadingStatus,
) = LibraryBook(
    copyId = copyId,
    workId = "work-$copyId",
    editionId = "edition-$copyId",
    title = title,
    primaryAuthor = "サンプル著者",
    isbn13 = null,
    publisher = null,
    publishedYear = null,
    coverUrl = null,
    ndcCode = ndcCode,
    ndcEdition = null,
    classificationSource = ClassificationSource.MANUAL,
    mediaType = MediaType.PHYSICAL,
    location = "サンプル書斎",
    readingStatus = status,
    addedAt = 1,
)
