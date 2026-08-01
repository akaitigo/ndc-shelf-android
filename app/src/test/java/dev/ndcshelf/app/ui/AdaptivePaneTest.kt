package dev.ndcshelf.app.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.BookSeries
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.SeriesMembership
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesVolume
import dev.ndcshelf.app.ui.adaptive.AdaptiveLayout
import dev.ndcshelf.app.ui.adaptive.EMPTY_DETAIL_PANE_TEST_TAG
import dev.ndcshelf.app.ui.screens.LIBRARY_LIST_TEST_TAG
import dev.ndcshelf.app.ui.screens.LibraryScreen
import dev.ndcshelf.app.ui.screens.SeriesScreen
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * list-detailの2ペイン表示と、姿勢・サイズクラス変更をまたいだ状態保持の回帰テスト。
 *
 * 「姿勢変更」は本アプリでは常にウィンドウ幅の変化として現れるため、
 * サイズクラス由来の`twoPane`フラグの切り替えで再現する。
 * プロセス再生成は`StateRestorationTester`で再現する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = EXPANDED_QUALIFIERS)
class AdaptivePaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun expandedLibraryShowsListAndDetailSideBySide() {
        composeRule.setContent {
            NdcShelfTheme { LibraryPane(twoPane = true) }
        }

        // 未選択でも一覧の隣に案内を出し、空白のペインにしない。
        composeRule.onNodeWithTag(EMPTY_DETAIL_PANE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()

        composeRule.onNodeWithText(bookTitle(0)).performClick()
        composeRule.waitForIdle()

        // 一覧（見出し）と詳細が同時に見えている。
        composeRule.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.book_detail_title)).assertIsDisplayed()
        // 一覧が見えているため、詳細ペインには戻る導線を出さない。
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.book_detail_back))
            .assertDoesNotExist()
    }

    @Test
    fun compactLibraryReplacesListWithDetail() {
        composeRule.setContent {
            NdcShelfTheme { LibraryPane(twoPane = false) }
        }

        composeRule.onNodeWithText(bookTitle(0)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.book_detail_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.library_title)).assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.book_detail_back))
            .assertIsDisplayed()
    }

    @Test
    fun sizeClassChangeKeepsSelectionAndEditedQuery() {
        var twoPane by mutableStateOf(false)
        composeRule.setContent {
            NdcShelfTheme { LibraryPane(twoPane = twoPane) }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.library_search_placeholder))
            .performTextInput(QUERY)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bookTitle(0)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.book_detail_title)).assertIsDisplayed()

        // compact → expanded（横画面・分割画面・折りたたみ展開に相当）
        twoPane = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.book_detail_title)).assertIsDisplayed()
        composeRule.onNodeWithText(QUERY).assertIsDisplayed()

        // expanded → compact へ戻しても選択と入力を失わない。
        twoPane = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.book_detail_title)).assertIsDisplayed()
    }

    @Test
    fun selectionAndQuerySurviveProcessRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            NdcShelfTheme { LibraryPane(twoPane = true) }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.library_search_placeholder))
            .performTextInput(QUERY)
        composeRule.onNodeWithText(bookTitle(0)).performClick()
        composeRule.waitForIdle()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.book_detail_title)).assertIsDisplayed()
        composeRule.onNodeWithText(QUERY).assertIsDisplayed()
    }

    @Test
    fun listScrollPositionSurvivesProcessRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            NdcShelfTheme { LibraryPane(twoPane = true) }
        }

        composeRule.onNodeWithTag(LIBRARY_LIST_TEST_TAG).performScrollToIndex(BOOK_COUNT - 1)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bookTitle(BOOK_COUNT - 1)).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(bookTitle(BOOK_COUNT - 1)).assertIsDisplayed()
    }

    @Test
    fun expandedSeriesShowsCatalogAndVolumesSideBySide() {
        var selectedSeriesId by mutableStateOf<String?>(null)
        composeRule.setContent {
            NdcShelfTheme {
                SeriesScreen(
                    series = listOf(seriesOverview()),
                    selectedSeriesId = selectedSeriesId,
                    onSelectSeries = { selectedSeriesId = it },
                    onOpenEdition = {},
                    onOpenBookstore = {},
                    twoPane = true,
                    listPaneWidth = AdaptiveLayout.LIST_PANE_WIDTH,
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule.onNodeWithTag(EMPTY_DETAIL_PANE_TEST_TAG).assertIsDisplayed()

        composeRule.onNodeWithText(SERIES_NAME).performClick()
        composeRule.waitForIdle()

        // カタログ見出しと巻一覧が同時に見えており、戻る導線は出ない。
        composeRule.onNodeWithText(context.getString(R.string.series_title)).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.series_back))
            .assertDoesNotExist()
    }
}

private const val BOOK_COUNT = 12
private const val SERIES_NAME = "匿名サンプルシリーズ"
private const val QUERY = "匿名"

@Composable
private fun LibraryPane(twoPane: Boolean) {
    LibraryScreen(
        books = anonymousBooks(),
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
        twoPane = twoPane,
        listPaneWidth = AdaptiveLayout.LIST_PANE_WIDTH,
        contentPadding = PaddingValues(),
    )
}

private fun bookTitle(index: Int): String = "匿名サンプル図書${'A' + index}"

private fun anonymousBooks(): List<LibraryBook> =
    (0 until BOOK_COUNT).map { index ->
        LibraryBook(
            copyId = "copy-$index",
            workId = "work-$index",
            editionId = "edition-$index",
            title = bookTitle(index),
            primaryAuthor = "サンプル著者$index",
            isbn13 = null,
            publisher = "匿名出版社",
            publishedYear = 2026,
            coverUrl = null,
            ndcCode = "007.6",
            ndcEdition = "NDC10",
            classificationSource = ClassificationSource.MANUAL,
            mediaType = MediaType.PHYSICAL,
            location = "サンプル書斎",
            readingStatus = ReadingStatus.UNREAD,
            addedAt = 1_753_000_000_000,
            bibliographicSource = BibliographicSource.MANUAL,
        )
    }

private fun seriesOverview(): SeriesOverview =
    SeriesOverview(
        series = BookSeries("series", SERIES_NAME, 1, 1_700_000_000_000),
        volumes =
            listOf(
                SeriesVolume(
                    membership =
                        SeriesMembership(
                            id = "membership-1",
                            seriesId = "series",
                            workId = "work-1",
                            workTitle = "$SERIES_NAME 1巻",
                            primaryAuthor = "サンプル著者",
                            sortOrderKey = "1",
                            volumeLabel = "1巻",
                            type = SeriesMembershipType.MAIN_STORY,
                            createdAt = 1,
                            updatedAt = 2,
                        ),
                    ownedEditionId = "edition-1",
                    bookstoreIsbn = null,
                    ownedCopyCount = 1,
                    readCopyCount = 0,
                    readingCopyCount = 0,
                    purchaseStatus = null,
                    latestOwnedAddedAt = 3,
                ),
            ),
    )
