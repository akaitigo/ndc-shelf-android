package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.ndcshelf.app.SeriesEditorUiState
import dev.ndcshelf.app.domain.model.BookSeries
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesSuggestion
import dev.ndcshelf.app.domain.model.SeriesSuggestionConfidence
import dev.ndcshelf.app.domain.model.SeriesSuggestionRule
import dev.ndcshelf.app.domain.repository.SeriesConfirmationDraft
import dev.ndcshelf.app.domain.repository.SeriesConfirmationTarget
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SeriesSuggestionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun candidatesAreGroupedButNeverConfirmedWithoutExplicitAction() {
        var confirmation: Pair<SeriesConfirmationTarget, List<SeriesConfirmationDraft>>? = null
        setContent(
            suggestions = listOf(suggestion("one", "1巻"), suggestion("two", "2巻")),
            onConfirm = { target, drafts -> confirmation = target to drafts },
        )

        composeRule.onNodeWithTag(SERIES_SUGGESTIONS_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("年代記").assertIsDisplayed()
        composeRule.onNodeWithText("候補 2冊").assertIsDisplayed()
        assertNull(confirmation)
    }

    @Test
    fun batchEditorAllowsLabelOrderAndNewSeriesEditsBeforeConfirmation() {
        var confirmation: Pair<SeriesConfirmationTarget, List<SeriesConfirmationDraft>>? = null
        setContent(
            suggestions = listOf(suggestion("one", "1巻"), suggestion("two", "2巻")),
            onConfirm = { target, drafts -> confirmation = target to drafts },
        )
        composeRule.onNodeWithText("年代記").performClick()
        composeRule.onNodeWithTag(SERIES_EDITOR_TEST_TAG).assertIsDisplayed()

        scrollEditorTo(hasTestTag(SERIES_NAME_FIELD_TEST_TAG))
        composeRule.onNodeWithTag(SERIES_NAME_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(SERIES_NAME_FIELD_TEST_TAG).performTextInput("年代記 完全版")
        scrollEditorTo(hasTestTag(SERIES_VOLUME_FIELD_TEST_TAG_PREFIX + "one"))
        val firstVolume = composeRule.onNodeWithTag(SERIES_VOLUME_FIELD_TEST_TAG_PREFIX + "one")
        firstVolume.performTextClearance()
        firstVolume.performTextInput("上巻")
        val moveSecondUp = "年代記 2巻を刊行順で上へ"
        scrollEditorTo(hasContentDescription(moveSecondUp))
        composeRule.onNodeWithContentDescription(moveSecondUp).performClick()
        scrollEditorTo(hasText("選択した2冊を確定"))
        composeRule.onNodeWithText("選択した2冊を確定").performClick()

        assertEquals(SeriesConfirmationTarget.New("年代記 完全版"), confirmation?.first)
        assertEquals(listOf("two", "one"), confirmation?.second?.map { it.workId })
        assertEquals("上巻", confirmation?.second?.last()?.volumeLabel)
        assertEquals(
            SeriesMembershipOrigin.TITLE_SUGGESTION,
            confirmation?.second?.last()?.origin,
        )
    }

    @Test
    fun existingSeriesCanBeSelectedAndLowConfidenceIsClearlyMarked() {
        var target: SeriesConfirmationTarget? = null
        setContent(
            suggestions = listOf(
                suggestion("side", "外伝").copy(
                    proposedType = SeriesMembershipType.SIDE_STORY,
                    confidence = SeriesSuggestionConfidence.LOW,
                    rule = SeriesSuggestionRule.SIDE_STORY_SUFFIX,
                ),
            ),
            catalog = listOf(SeriesOverview(BookSeries("existing", "保存済み年代記", 1, 2), emptyList())),
            onConfirm = { selected, _ -> target = selected },
        )
        composeRule.onNodeWithText("年代記").performClick()
        composeRule.onNodeWithText("低信頼度・要確認").assertIsDisplayed()
        composeRule.onNodeWithText("既存シリーズ").performClick()
        composeRule.onNodeWithText("保存済み年代記").performClick()
        scrollEditorTo(hasText("選択した1冊を確定"))
        composeRule.onNodeWithText("選択した1冊を確定").performClick()

        assertEquals(SeriesConfirmationTarget.Existing("existing"), target)
    }

    private fun setContent(
        suggestions: List<SeriesSuggestion>,
        catalog: List<SeriesOverview> = emptyList(),
        onConfirm: (SeriesConfirmationTarget, List<SeriesConfirmationDraft>) -> Unit,
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                SeriesSuggestionScreen(
                    suggestions = suggestions,
                    catalog = catalog,
                    focusedSuggestion = null,
                    state = SeriesEditorUiState.Idle,
                    onConfirm = onConfirm,
                    onBack = {},
                    onSaved = {},
                    onClearState = {},
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    private fun scrollEditorTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNodeWithTag(SERIES_EDITOR_TEST_TAG).performScrollToNode(matcher)
    }

    private fun suggestion(workId: String, label: String) = SeriesSuggestion(
        workId = workId,
        sourceTitle = "年代記 $label",
        proposedSeriesName = "年代記",
        proposedVolumeLabel = label,
        proposedType = SeriesMembershipType.MAIN_STORY,
        confidence = SeriesSuggestionConfidence.HIGH,
        rule = SeriesSuggestionRule.EXPLICIT_VOLUME,
    )
}
