package dev.ndcshelf.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ndcshelf.app.BookstoreUiState
import dev.ndcshelf.app.domain.model.BookstoreBook
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookstoreResultCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ownedAndReservedStatesAreProminentAndCanBePurchased() {
        var transition: PurchaseTransition? = null
        composeRule.setContent {
            NdcShelfTheme {
                BookstoreResultCard(
                    state = BookstoreUiState.Result(book()),
                    onRetry = {},
                    onCameraRetry = {},
                    onClear = {},
                    onChangeState = { transition = it },
                )
            }
        }

        composeRule.onNodeWithText("2冊").assertIsDisplayed()
        composeRule.onNodeWithText("所有").assertIsDisplayed()
        composeRule.onNodeWithText("予約済み").assertIsDisplayed()
        composeRule.onNodeWithText("購入済みとして本棚へ追加").performClick()
        assertEquals(PurchaseTransition.PURCHASED, transition)
    }

    private fun book() = BookstoreBook(
        workId = "work",
        editionId = "edition",
        title = "テスト本",
        primaryAuthor = "著者",
        isbn13 = "9784101010014",
        publisher = null,
        publishedYear = null,
        coverUrl = null,
        ndcCode = null,
        ndcEdition = null,
        classificationSource = ClassificationSource.UNKNOWN,
        purchaseStatus = PurchaseStatus.RESERVED,
        ownedCopyCount = 2,
    )
}
