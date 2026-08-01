package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.R
import dev.ndcshelf.app.WorkVariantUiState
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.EditionVariant
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.WorkVariant
import dev.ndcshelf.app.domain.model.WorkVariantEditor
import dev.ndcshelf.app.domain.model.WorkVariantSuggestion
import dev.ndcshelf.app.domain.model.WorkVariantSuggestionConfidence
import dev.ndcshelf.app.domain.text.UiMessage
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class WorkVariantScreenTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun candidateRequiresDiffConfirmationBeforeLinking() {
        var linked: Pair<String, Boolean>? = null
        val source = variant("source", "作品 新装版", "9784000000015", "出版社A", 2020)
        val target = variant("target", "作品（文庫版）", "9784000000022", "出版社B", 2024)
        composeRule.setContent {
            NdcShelfTheme {
                WorkVariantScreen(
                    state = WorkVariantUiState.Ready(
                        WorkVariantEditor(
                            source = source,
                            group = null,
                            groupMembers = emptyList(),
                            suggestions = listOf(
                                WorkVariantSuggestion(
                                    target,
                                    WorkVariantSuggestionConfidence.HIGH,
                                    UiMessage(R.string.work_variant_reason_title_author),
                                ),
                            ),
                        ),
                    ),
                    onBack = {},
                    onLink = { id, substitute -> linked = id to substitute },
                    onUnlink = {},
                    onSetSeriesSubstitution = { _, _ -> },
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule.onNodeWithText("作品（文庫版）").assertIsDisplayed()
        assertNull(linked)
        composeRule.onNodeWithText("作品（文庫版）").performClick()
        composeRule.onNodeWithText(context.getString(R.string.work_variant_confirm_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.work_variant_confirm)).performClick()

        assertEquals("target" to false, linked)
    }

    private fun variant(id: String, title: String, isbn: String, publisher: String, year: Int) =
        WorkVariant(
            workId = id,
            title = title,
            primaryAuthor = "著者",
            editions = listOf(
                EditionVariant(
                    id = "$id-edition",
                    isbn13 = isbn,
                    publisher = publisher,
                    publishedYear = year,
                    coverUrl = null,
                    ndcCode = "913.6",
                    ndcEdition = "NDC10",
                    classificationSource = ClassificationSource.NDL,
                    bibliographicSource = BibliographicSource.NDL,
                    mediaTypes = setOf(MediaType.PHYSICAL),
                    ownedCopyCount = 1,
                    wishlistStatus = null,
                ),
            ),
        )
}
