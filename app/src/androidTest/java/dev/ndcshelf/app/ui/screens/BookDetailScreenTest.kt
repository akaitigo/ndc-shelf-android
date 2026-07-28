package dev.ndcshelf.app.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingCoverLongTextUnknownClassificationAndMultipleCopiesAreVisible() {
        val first = book().copy(
            title = "非常に長いタイトルの郷土資料・完全保存版・改訂版",
            primaryAuthor = "非常に長い著者名と編者名の共同執筆者一覧",
        )
        var editedCopyId: String? = null
        composeRule.setContent {
            NdcShelfTheme {
                BookDetailScreen(
                    copies = listOf(
                        first.copy(copyId = "copy-1", copyLabel = "閲覧用"),
                        first.copy(
                            copyId = "copy-2",
                            copyLabel = "保存用",
                            location = "書庫",
                            mediaType = MediaType.DIGITAL,
                        ),
                    ),
                    onBack = {},
                    onEditCopy = { editedCopyId = it },
                    onEditBibliography = {},
                    onReconcile = {},
                    contentPadding = PaddingValues(),
                )
            }
        }

        composeRule.onNodeWithTag(BOOK_DETAIL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(first.title).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("${first.title} の表紙なし").assertIsDisplayed()
        scrollDetailTo(hasText("未分類"))
        composeRule.onNodeWithText("未分類").assertIsDisplayed()
        scrollDetailTo(hasText("所有コピー 2冊"))
        composeRule.onNodeWithText("所有コピー 2冊").assertIsDisplayed()
        scrollDetailTo(hasText("紙・電子"))
        composeRule.onNodeWithText("紙・電子").assertIsDisplayed()
        scrollDetailTo(hasText("NDLから再取得・照合"))
        composeRule.onNodeWithText("NDLから再取得・照合").assertIsDisplayed()
        scrollDetailTo(hasText("シリーズを整理（準備中）"))
        composeRule.onNodeWithText("シリーズを整理（準備中）")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        scrollDetailTo(hasContentDescription("保存用、場所 書庫、未読、電子。タップして編集"))
        composeRule.onNodeWithContentDescription(
            "保存用、場所 書庫、未読、電子。タップして編集",
        ).performClick()
        assertEquals("copy-2", editedCopyId)
    }

    @Test
    fun initialEditionIdOpensDetailForDeepLink() {
        composeRule.setContent { Library(listOf(book()), initialEditionId = "edition") }

        composeRule.onNodeWithTag(BOOK_DETAIL_TEST_TAG).assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Library(books: List<LibraryBook>, initialEditionId: String? = null) {
        NdcShelfTheme {
            LibraryScreen(
                books = books,
                initialEditionId = initialEditionId,
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

    private fun book() = LibraryBook(
        copyId = "copy",
        workId = "work",
        editionId = "edition",
        title = "郷土資料",
        primaryAuthor = "著者不明",
        isbn13 = null,
        publisher = null,
        publishedYear = null,
        coverUrl = null,
        ndcCode = null,
        ndcEdition = null,
        classificationSource = ClassificationSource.UNKNOWN,
        mediaType = MediaType.PHYSICAL,
        location = "未設定",
        readingStatus = ReadingStatus.UNREAD,
        addedAt = 1,
        bibliographicSource = BibliographicSource.MANUAL,
    )

    private fun scrollDetailTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNodeWithTag(BOOK_DETAIL_TEST_TAG).performScrollToNode(matcher)
    }
}

class BookDetailRestorationTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun selectedEditionSurvivesSavedStateRestorationAndBackReturnsToLibrary() =
        runComposeUiTest {
            val restoration = StateRestorationTester(this)
            restoration.setContent { Library(listOf(book())) }

            onNodeWithText("郷土資料").performClick()
            onNodeWithTag(BOOK_DETAIL_TEST_TAG).assertIsDisplayed()

            restoration.emulateSaveAndRestore()

            onNodeWithTag(BOOK_DETAIL_TEST_TAG).assertIsDisplayed()
            onNodeWithContentDescription("本棚へ戻る").performClick()
            onNodeWithText("My Library").assertIsDisplayed()
        }

    @androidx.compose.runtime.Composable
    private fun Library(books: List<LibraryBook>) {
        NdcShelfTheme {
            LibraryScreen(
                books = books,
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

    private fun book() = LibraryBook(
        copyId = "copy",
        workId = "work",
        editionId = "edition",
        title = "郷土資料",
        primaryAuthor = "著者不明",
        isbn13 = null,
        publisher = null,
        publishedYear = null,
        coverUrl = null,
        ndcCode = null,
        ndcEdition = null,
        classificationSource = ClassificationSource.UNKNOWN,
        mediaType = MediaType.PHYSICAL,
        location = "未設定",
        readingStatus = ReadingStatus.UNREAD,
        addedAt = 1,
        bibliographicSource = BibliographicSource.MANUAL,
    )
}
