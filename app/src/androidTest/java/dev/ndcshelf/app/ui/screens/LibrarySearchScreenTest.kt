package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResultUtils
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.hamcrest.Matchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LibrarySearchScreenTest {
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
    fun statusAndSortControlsPublishSelectedCriteria() {
        var status: ReadingStatus? = null
        var sort: LibrarySort? = null
        setContent(
            criteria = LibrarySearchCriteria(),
            onStatusChange = { status = it },
            onSortChange = { sort = it },
        )

        composeRule.onNode(hasText(context.getString(R.string.insights_metric_reading)) and hasClickAction()).performClick()
        composeRule.onNodeWithText(context.getString(R.string.library_sort_title)).performClick()

        assertEquals(ReadingStatus.READING, status)
        assertEquals(LibrarySort.TITLE, sort)
    }

    @Test
    fun staleResultsAreHiddenWhileDebouncedQueryIsPending() {
        setContent(
            criteria = LibrarySearchCriteria(query = "new"),
            searchIsCurrent = false,
        )

        composeRule.onNodeWithTag(LIBRARY_SEARCH_PROGRESS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("new").assertIsDisplayed()
        composeRule.onAllNodesWithText("古い結果").assertCountEquals(0)
    }

    private fun setContent(
        criteria: LibrarySearchCriteria,
        searchIsCurrent: Boolean = true,
        onStatusChange: (ReadingStatus?) -> Unit = {},
        onSortChange: (LibrarySort) -> Unit = {},
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                LibraryScreen(
                    books = listOf(book()),
                    searchCriteria = criteria,
                    searchIsCurrent = searchIsCurrent,
                    libraryStats = LibraryStats(1, 0, 0),
                    onReadingStatusChange = onStatusChange,
                    onSortChange = onSortChange,
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
        }
    }

    private fun book() = LibraryBook(
        copyId = "copy",
        workId = "work",
        editionId = "edition",
        title = "古い結果",
        primaryAuthor = "著者",
        isbn13 = null,
        publisher = null,
        publishedYear = null,
        coverUrl = null,
        ndcCode = null,
        ndcEdition = null,
        classificationSource = ClassificationSource.UNKNOWN,
        mediaType = MediaType.PHYSICAL,
        location = "棚",
        readingStatus = ReadingStatus.UNREAD,
        addedAt = 1,
    )
}
