package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LocationRoom
import dev.ndcshelf.app.domain.model.LocationShelf
import dev.ndcshelf.app.domain.model.LocationTier
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Rule
import org.junit.Test

class LibraryLocationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun locationManagerShowsEmptyAndNestedNodes() {
        setLibraryContent(emptyList())

        composeRule.onNodeWithText("置き場所を管理").performClick()

        composeRule.onNodeWithText("従来の自由入力は自動変換されません", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("書斎").assertIsDisplayed()
        composeRule.onNodeWithText("本棚A").assertIsDisplayed()
        composeRule.onNodeWithText("上段").assertIsDisplayed()
    }

    @Test
    fun bookEditorCanSelectRegisteredTier() {
        setLibraryContent(listOf(book()))

        composeRule.onNodeWithText("テスト本").performClick()

        composeRule.onNodeWithText("登録済みの段").assertIsDisplayed()
        composeRule.onNodeWithText("書斎 / 本棚A / 上段").assertIsDisplayed()
    }

    @Test
    fun bookEditorShowsNeighborsAndAccessibleMoveActions() {
        val books = listOf(
            book().copy(copyId = "left", title = "左の本", shelfOrderKey = "20"),
            book().copy(copyId = "middle", title = "中央の本", shelfOrderKey = "40"),
            book().copy(copyId = "right", title = "右の本", shelfOrderKey = "60"),
        )
        setLibraryContent(books)

        composeRule.onNodeWithText("中央の本").performClick()

        composeRule.onNodeWithText("左: 左の本").assertIsDisplayed()
        composeRule.onNodeWithText("右: 右の本").assertIsDisplayed()
        composeRule.onNodeWithText("左へ移動").assertIsEnabled()
        composeRule.onNodeWithText("右へ移動").assertIsEnabled()
        composeRule.onNodeWithText("棚へ入れる位置").assertIsDisplayed()
    }

    @Test
    fun listShowsCopyLabelsAndEditionCopyCount() {
        val books = listOf(
            book().copy(copyId = "copy-1", copyLabel = "保存用"),
            book().copy(copyId = "copy-2", copyLabel = "貸出用"),
        )
        setLibraryContent(books)

        composeRule.onNodeWithText("保存用 ・ 同じ版を2冊所蔵").assertIsDisplayed()
        composeRule.onNodeWithText("貸出用 ・ 同じ版を2冊所蔵").assertIsDisplayed()

        composeRule.onNodeWithText("保存用 ・ 同じ版を2冊所蔵").performClick()
        composeRule.onNodeWithText("コピーごとの情報").assertIsDisplayed()
        composeRule.onNodeWithText("同じ版で共通の情報").assertIsDisplayed()
    }

    private fun setLibraryContent(books: List<LibraryBook>) {
        composeRule.setContent {
            NdcShelfTheme {
                LibraryScreen(
                    books = books,
                    onSaveBook = { _, _ -> },
                    onDeleteBook = {},
                    bookEditState = BookEditUiState.Idle,
                    onClearBookEditState = {},
                    bookDeleteState = BookDeleteUiState.Idle,
                    onClearBookDeleteState = {},
                    locations = tree(),
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

    private fun tree() = LocationTree(
        listOf(
            LocationRoom(
                id = "room",
                name = "書斎",
                sortOrder = 0,
                shelves = listOf(
                    LocationShelf(
                        id = "shelf",
                        roomId = "room",
                        name = "本棚A",
                        sortOrder = 0,
                        tiers = listOf(LocationTier("tier", "shelf", "上段", 0, 1)),
                    ),
                ),
            ),
        ),
    )

    private fun book() = LibraryBook(
        copyId = "copy",
        workId = "work",
        editionId = "edition",
        title = "テスト本",
        primaryAuthor = "著者",
        isbn13 = "9784101010014",
        publisher = null,
        publishedYear = null,
        coverUrl = null,
        ndcCode = "913.6",
        ndcEdition = "10",
        classificationSource = ClassificationSource.NDL,
        mediaType = MediaType.PHYSICAL,
        location = "書斎 / 本棚A / 上段",
        readingStatus = ReadingStatus.UNREAD,
        addedAt = 1,
        locationTierId = "tier",
    )
}
