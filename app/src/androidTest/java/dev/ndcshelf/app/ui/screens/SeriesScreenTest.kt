package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ndcshelf.app.domain.model.BookSeries
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.SeriesMembership
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesVolume
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SeriesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyCatalogExplainsHowSeriesWillAppear() {
        setContent(emptyList())

        composeRule.onNodeWithTag(SERIES_LIST_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SERIES_EMPTY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("シリーズはまだありません").assertIsDisplayed()
    }

    @Test
    fun singleVolumeCatalogShowsOwnedKnownReadAndLatestCounts() {
        var selected: String? = null
        val overview = overview(
            volume("1巻", ownedEditionId = "edition-1", readCopies = 1),
        )
        setContent(listOf(overview), onSelectSeries = { selected = it })

        composeRule.onNodeWithText("所有 1 / 既知 1 ・ 読了 1").assertIsDisplayed()
        composeRule.onNodeWithText("最新の所有巻: 1巻").assertIsDisplayed()
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
        composeRule.onNodeWithText("確認済みの本編をすべて所有しています").assertIsDisplayed()
        composeRule.onNodeWithText("本の詳細").performClick()
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

        composeRule.onNodeWithText("予約済み").assertIsDisplayed()
        composeRule.onNodeWithText("確認済みの未所有本編").assertIsDisplayed()
        composeRule.onNodeWithText("書店モード").performClick()
        assertEquals("9784000000039", openedIsbn)
    }

    @Test
    fun sideStoryCanBeUnownedWithoutBeingMarkedMissing() {
        val overview = overview(
            volume("外伝", type = SeriesMembershipType.SIDE_STORY, isbn = "9784000000046"),
        )
        setContent(listOf(overview), selectedSeriesId = "series")

        composeRule.onNodeWithText("銀河叙事詩 外伝").assertIsDisplayed()
        composeRule.onNodeWithText("未所有").assertIsDisplayed()
        composeRule.onNodeWithText("確認済みの未所有本編").assertDoesNotExist()
    }

    @Test
    fun omnibusAndUnnumberedVolumeAreExcludedFromMissingCandidates() {
        val overview = overview(
            volume("1-2巻", type = SeriesMembershipType.OMNIBUS),
            volume("巻番号なし"),
        )
        setContent(listOf(overview), selectedSeriesId = "series")

        composeRule.onNodeWithText("合本").assertIsDisplayed()
        composeRule.onNodeWithText("欠巻候補").assertTextContains("欠巻候補")
        composeRule.onNodeWithText("確認済みの未所有本編").assertDoesNotExist()
    }

    private fun setContent(
        series: List<SeriesOverview>,
        selectedSeriesId: String? = null,
        onSelectSeries: (String?) -> Unit = {},
        onOpenEdition: (String) -> Unit = {},
        onOpenBookstore: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            NdcShelfTheme {
                SeriesScreen(
                    series = series,
                    selectedSeriesId = selectedSeriesId,
                    onSelectSeries = onSelectSeries,
                    onOpenEdition = onOpenEdition,
                    onOpenBookstore = onOpenBookstore,
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
