package dev.ndcshelf.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.InsightsUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.insights.InsightsMonth
import dev.ndcshelf.app.domain.insights.LibraryInsights
import dev.ndcshelf.app.domain.insights.LibraryInsightsCalculator
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.PartialDate
import dev.ndcshelf.app.domain.model.ReadingSession
import dev.ndcshelf.app.domain.model.ReadingSessionStatus
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InsightsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val calculator = LibraryInsightsCalculator()
    private val now = 1_753_000_000_000L
    private val currentMonth = InsightsMonth(2026, 7)

    @Test
    fun privacyNoteAndReasonTextsAreAlwaysVisible() {
        val state =
            readyState(
                books = listOf(book("copy-1", addedAt = now - days(420))),
            )
        setContent(state)

        composeRule
            .onNodeWithText(context.getString(R.string.insights_privacy_note))
            .assertIsDisplayed()
        scrollTo(context.resources.getQuantityString(R.plurals.insights_reason_unread, 420, 420L))
        composeRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.insights_reason_unread, 420, 420L))
            .assertIsDisplayed()
    }

    @Test
    fun insufficientHistoryExplainsRequiredDataInsteadOfChart() {
        val state =
            readyState(
                books = listOf(book("copy-1", addedAt = now, readingStatus = ReadingStatus.READ)),
                sessions = listOf(finished("session-1", "copy-1", "2026-07-01")),
            )
        setContent(state)

        scrollTo(context.getString(R.string.insights_trend_insufficient, 3, 1))
        composeRule
            .onNodeWithText(context.getString(R.string.insights_trend_insufficient, 3, 1))
            .assertIsDisplayed()
    }

    @Test
    fun trendChartExposesMonthlyCountsViaContentDescription() {
        val sessions =
            listOf(
                finished("session-1", "copy-1", "2026-07-01"),
                finished("session-2", "copy-1", "2026-07-10"),
                finished("session-3", "copy-1", "2026-06-01"),
            )
        val state =
            readyState(
                books = listOf(book("copy-1", addedAt = now, readingStatus = ReadingStatus.READ)),
                sessions = sessions,
            )
        setContent(state)

        val julyDescription = context.resources.getQuantityString(
            R.plurals.insights_trend_bar_description,
            2,
            2026,
            7,
            2,
        )
        composeRule
            .onNodeWithTag("insights-list")
            .performScrollToNode(hasContentDescription(julyDescription))
        composeRule.onNodeWithContentDescription(julyDescription).assertIsDisplayed()
    }

    @Test
    fun excludeButtonReportsCopyId() {
        var excluded: String? = null
        val state = readyState(books = listOf(book("copy-1", addedAt = now - days(30))))
        setContent(state, onExcludeBook = { excluded = it })

        scrollTo(context.getString(R.string.insights_exclude_button))
        composeRule
            .onAllNodesWithText(context.getString(R.string.insights_exclude_button))
            .onFirst()
            .performClick()

        assertEquals("copy-1", excluded)
    }

    @Test
    fun analysisResetRequiresConfirmationBeforeCallback() {
        var resetCount = 0
        val state = readyState(books = listOf(book("copy-1", addedAt = now)))
        setContent(state, onResetExclusions = { resetCount += 1 })

        scrollTo(context.getString(R.string.insights_reset_button))
        composeRule.onNodeWithText(context.getString(R.string.insights_reset_button)).performClick()
        assertEquals(0, resetCount)

        composeRule
            .onNodeWithText(context.getString(R.string.insights_reset_dialog_message))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.insights_reset_confirm)).performClick()

        assertEquals(1, resetCount)
    }

    @Test
    fun ndcRowsCarryTextualShareForTalkBack() {
        val state =
            readyState(
                books =
                    listOf(
                        book("copy-1", addedAt = now, ndcCode = "913"),
                        book("copy-2", addedAt = now, ndcCode = "914"),
                    ),
            )
        setContent(state)

        val description =
            context.resources.getQuantityString(
                R.plurals.insights_ndc_row_description,
                2,
                9,
                context.getString(R.string.ndc_category_9),
                2,
                100,
            )
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    private fun setContent(
        state: InsightsUiState,
        onExcludeBook: (String) -> Unit = {},
        onResetExclusions: () -> Unit = {},
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                InsightsScreen(
                    state = state,
                    onExcludeBook = onExcludeBook,
                    onResetExclusions = onResetExclusions,
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeRule
            .onNodeWithTag("insights-list")
            .performScrollToNode(hasText(text))
    }

    private fun readyState(
        books: List<LibraryBook>,
        sessions: List<ReadingSession> = emptyList(),
        excludedCopyIds: Set<String> = emptySet(),
    ): InsightsUiState.Ready {
        val insights: LibraryInsights =
            calculator.calculate(
                books = books,
                sessions = sessions,
                excludedCopyIds = excludedCopyIds,
                nowMillis = now,
                currentMonth = currentMonth,
                rediscoverySeed = 42L,
            )
        return InsightsUiState.Ready(books = books, insights = insights)
    }

    private fun days(count: Long): Long = count * 24 * 60 * 60 * 1000

    private fun book(
        copyId: String,
        addedAt: Long,
        readingStatus: ReadingStatus = ReadingStatus.UNREAD,
        ndcCode: String? = null,
    ): LibraryBook =
        LibraryBook(
            copyId = copyId,
            workId = "work-$copyId",
            editionId = "edition-$copyId",
            title = "タイトル$copyId",
            primaryAuthor = "著者",
            isbn13 = null,
            publisher = null,
            publishedYear = null,
            coverUrl = null,
            ndcCode = ndcCode,
            ndcEdition = null,
            classificationSource = ClassificationSource.MANUAL,
            mediaType = MediaType.PHYSICAL,
            location = "本棚",
            readingStatus = readingStatus,
            addedAt = addedAt,
        )

    private fun finished(
        id: String,
        copyId: String,
        finishedDay: String,
    ): ReadingSession =
        ReadingSession(
            id = id,
            copyId = copyId,
            copyLabel = "所蔵本",
            status = ReadingSessionStatus.FINISHED,
            startedDay = null,
            finishedDay = requireNotNull(PartialDate.parse(finishedDay)),
            rating = null,
            note = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
}
