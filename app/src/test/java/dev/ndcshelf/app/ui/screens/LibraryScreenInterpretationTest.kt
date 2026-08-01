package dev.ndcshelf.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.NdcCategory
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.search.SearchInterpretationChip
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 解釈チップの表示・個別解除・空結果ガイダンスとアクセシビリティラベルの検証。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryScreenInterpretationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun showsInterpretationChipsWithDismissActionAndEmptyGuidance() {
        val dismissed = mutableListOf<String>()
        val statusChip = SearchInterpretationChip.Status(ReadingStatus.UNREAD)
        val ndcChip =
            SearchInterpretationChip.Ndc(requireNotNull(NdcCategory.fromCode("4")))
        composeRule.setContent {
            NdcShelfTheme {
                LibraryScreen(
                    books = emptyList(),
                    searchCriteria = LibrarySearchCriteria(query = "未読の自然科学"),
                    interpretationChips = listOf(statusChip, ndcChip),
                    onDismissInterpretationChip = dismissed::add,
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

        composeRule
            .onNodeWithText(context.getString(R.string.nl_search_interpretation_label))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(interpretationChipTag(statusChip.id)).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(
                    R.string.nl_search_chip_ndc,
                    4,
                    context.getString(R.string.ndc_category_4),
                ),
            ).assertIsDisplayed()

        // 空結果時は解釈チップの解除を促すガイダンスを表示する。
        composeRule
            .onNodeWithText(context.getString(R.string.nl_search_empty_hint))
            .assertExists()

        // ×アイコンにはチップ名入りのcontentDescriptionを付け、タップで個別解除する。
        composeRule
            .onNodeWithContentDescription(
                context.getString(
                    R.string.nl_search_chip_dismiss,
                    context.getString(R.string.reading_status_unread),
                ),
                useUnmergedTree = true,
            ).assertExists()
        composeRule.onNodeWithTag(interpretationChipTag(statusChip.id)).performClick()
        assertEquals(listOf(statusChip.id), dismissed)
    }

    @Test
    fun hidesInterpretationRowWhenThereAreNoChips() {
        composeRule.setContent {
            NdcShelfTheme {
                LibraryScreen(
                    books = emptyList(),
                    searchCriteria = LibrarySearchCriteria(query = "面白い本"),
                    interpretationChips = emptyList(),
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

        assertTrue(
            composeRule
                .onAllNodesWithText(context.getString(R.string.nl_search_interpretation_label))
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        // 解釈がない検索は従来の空結果メッセージのまま。
        composeRule
            .onNodeWithText(context.getString(R.string.library_empty_searching_title))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.library_empty_searching_body))
            .assertExists()
    }
}
