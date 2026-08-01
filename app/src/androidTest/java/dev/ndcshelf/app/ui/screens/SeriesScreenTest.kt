package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.BookSeries
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.SeriesMembership
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesReleaseCandidate
import dev.ndcshelf.app.domain.model.SeriesReleaseState
import dev.ndcshelf.app.domain.model.SeriesWatch
import dev.ndcshelf.app.domain.model.SeriesWatchOverview
import dev.ndcshelf.app.domain.model.SeriesVolume
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SeriesScreenTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyCatalogExplainsHowSeriesWillAppear() {
        setContent(emptyList())

        composeRule.onNodeWithTag(SERIES_LIST_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SERIES_EMPTY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.series_empty_title)).assertIsDisplayed()
    }

    @Test
    fun singleVolumeCatalogShowsOwnedKnownReadAndLatestCounts() {
        var selected: String? = null
        val overview = overview(
            volume("1巻", ownedEditionId = "edition-1", readCopies = 1),
        )
        setContent(listOf(overview), onSelectSeries = { selected = it })

        composeRule
            .onNodeWithText(context.getString(R.string.series_catalog_counts, 1, 1, 1))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.series_latest_owned, "1巻"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("銀河叙事詩").performClick()
        assertEquals("series", selected)
    }

    @Test
    fun completeMainStoryShowsConfirmedCompletionAndOpensOwnedEdition() {
        var openedEdition: String? = null
        val overview = overview(volume("上巻", ownedEditionId = "edition-upper"))
        setContent(
            series = listOf(overview),
            selectedSeriesId = "series",
            onOpenEdition = { openedEdition = it },
        )

        composeRule.onNodeWithTag(SERIES_DETAIL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.series_confirmed_complete))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.book_detail_title)).performScrollTo().performClick()
        assertEquals("edition-upper", openedEdition)
    }

    @Test
    fun unownedConfirmedMainStoryIsMarkedMissingAndOpensBookstore() {
        var openedIsbn: String? = null
        val overview = overview(
            volume("2巻", isbn = "9784000000039", purchaseStatus = PurchaseStatus.RESERVED),
        )
        setContent(
            series = listOf(overview),
            selectedSeriesId = "series",
            onOpenBookstore = { openedIsbn = it },
        )

        composeRule.onNodeWithText(context.getString(R.string.bookstore_reserved)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.series_missing_candidate)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.scan_mode_bookstore)).performScrollTo().performClick()
        assertEquals("9784000000039", openedIsbn)
    }

    @Test
    fun sideStoryCanBeUnownedWithoutBeingMarkedMissing() {
        val overview = overview(
            volume("外伝", type = SeriesMembershipType.SIDE_STORY, isbn = "9784000000046"),
        )
        setContent(listOf(overview), selectedSeriesId = "series")

        composeRule.onNodeWithText("銀河叙事詩 外伝").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.series_state_unowned)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.series_missing_candidate)).assertDoesNotExist()
    }

    @Test
    fun omnibusAndUnnumberedVolumeAreExcludedFromMissingCandidates() {
        val overview = overview(
            volume("1-2巻", type = SeriesMembershipType.OMNIBUS),
            volume("巻番号なし"),
        )
        setContent(listOf(overview), selectedSeriesId = "series")

        composeRule.onNodeWithText(context.getString(R.string.series_type_omnibus)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.series_missing_label)).assertTextContains(context.getString(R.string.series_missing_label))
        composeRule.onNodeWithText(context.getString(R.string.series_missing_candidate)).assertDoesNotExist()
    }

    @Test
    fun releaseWatchIsOffByDefaultDisclosesTransmissionAndRequestsExplicitOptIn() {
        var mutation: Pair<String, Boolean>? = null
        setContent(
            series = listOf(overview(volume("1巻"))),
            selectedSeriesId = "series",
            onSetWatchEnabled = { seriesId, enabled -> mutation = seriesId to enabled },
        )

        composeRule.onNodeWithText(context.getString(R.string.series_watch_title)).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.series_watch_privacy, "銀河叙事詩"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()

        assertEquals("series" to true, mutation)
    }

    @Test
    fun releaseCandidateShowsPurchaseStateAndOpensBookstore() {
        var openedIsbn: String? = null
        val watch = SeriesWatchOverview(
            watch = SeriesWatch("series", "銀河叙事詩", true, 1, 2, 3, 3),
            candidates = listOf(
                SeriesReleaseCandidate(
                    id = "candidate",
                    seriesId = "series",
                    title = "銀河叙事詩 2巻",
                    primaryAuthor = "星野 著",
                    isbn13 = "9784000000039",
                    publisher = "星雲社",
                    publishedDate = "2026-08",
                    firstSeenAt = 3,
                    lastSeenAt = 3,
                    notifiedAt = null,
                    state = SeriesReleaseState.RESERVED,
                ),
            ),
        )
        setContent(
            series = listOf(overview(volume("1巻"))),
            watches = listOf(watch),
            selectedSeriesId = "series",
            onOpenBookstore = { openedIsbn = it },
        )

        composeRule.onNodeWithText("銀河叙事詩 2巻").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.bookstore_reserved)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.scan_mode_bookstore)).performScrollTo().performClick()

        assertEquals("9784000000039", openedIsbn)
    }

    private fun setContent(
        series: List<SeriesOverview>,
        watches: List<SeriesWatchOverview> = emptyList(),
        selectedSeriesId: String? = null,
        onSelectSeries: (String?) -> Unit = {},
        onOpenEdition: (String) -> Unit = {},
        onOpenBookstore: (String) -> Unit = {},
        onSetWatchEnabled: (String, Boolean) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                SeriesScreen(
                    series = series,
                    watches = watches,
                    selectedSeriesId = selectedSeriesId,
                    onSelectSeries = onSelectSeries,
                    onOpenEdition = onOpenEdition,
                    onOpenBookstore = onOpenBookstore,
                    onSetWatchEnabled = onSetWatchEnabled,
                    contentPadding = PaddingValues(),
                )
            }
        }
    }

    private fun overview(vararg volumes: SeriesVolume) = SeriesOverview(
        series = BookSeries("series", "銀河叙事詩", 1, 1_700_000_000_000),
        volumes = volumes.toList(),
    )

    private fun volume(
        label: String,
        type: SeriesMembershipType = SeriesMembershipType.MAIN_STORY,
        ownedEditionId: String? = null,
        isbn: String? = null,
        readCopies: Int = 0,
        purchaseStatus: PurchaseStatus? = null,
    ) = SeriesVolume(
        membership = SeriesMembership(
            id = "membership-$label",
            seriesId = "series",
            workId = "work-$label",
            workTitle = "銀河叙事詩 $label",
            primaryAuthor = "星野 著",
            sortOrderKey = label,
            volumeLabel = label,
            type = type,
            createdAt = 1,
            updatedAt = 2,
        ),
        ownedEditionId = ownedEditionId,
        bookstoreIsbn = isbn,
        ownedCopyCount = if (ownedEditionId == null) 0 else 1,
        readCopyCount = readCopies,
        readingCopyCount = 0,
        purchaseStatus = purchaseStatus,
        latestOwnedAddedAt = ownedEditionId?.let { 3 },
    )
}
