package dev.ndcshelf.app.ui

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.MainViewModel
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.InMemoryOnboardingStore
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.ui.adaptive.ADAPTIVE_NAVIGATION_BAR_TEST_TAG
import dev.ndcshelf.app.ui.adaptive.ADAPTIVE_NAVIGATION_RAIL_TEST_TAG
import dev.ndcshelf.app.ui.navigation.DataRoute
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ウィンドウ幅に応じたナビゲーションの切り替え（下部バー ⇔ 左レール）と、
 * 大画面でのキーボード操作・タップ領域の回帰テスト。
 * 方針は docs/ADAPTIVE_LAYOUT.md、読み上げ順は docs/ACCESSIBILITY_AUDIT.md を参照。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = COMPACT_QUALIFIERS)
class AdaptiveNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun compactWindowShowsBottomNavigationBar() {
        setAppContent()

        composeRule.onNodeWithTag(ADAPTIVE_NAVIGATION_BAR_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ADAPTIVE_NAVIGATION_RAIL_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = MEDIUM_QUALIFIERS)
    fun mediumWindowShowsNavigationRail() {
        setAppContent()

        composeRule.onNodeWithTag(ADAPTIVE_NAVIGATION_RAIL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ADAPTIVE_NAVIGATION_BAR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = EXPANDED_QUALIFIERS)
    fun expandedWindowShowsNavigationRail() {
        setAppContent()

        composeRule.onNodeWithTag(ADAPTIVE_NAVIGATION_RAIL_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ADAPTIVE_NAVIGATION_BAR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = EXPANDED_QUALIFIERS)
    fun railItemNavigatesOnClick() {
        val navController = setAppContent()

        composeRule.onNodeWithText(context.getString(R.string.navigation_data)).performClick()
        composeRule.waitForIdle()

        val current = requireNotNull(navController.currentDestination)
        assertTrue(current.hasRoute<DataRoute>())
    }

    @Test
    @Config(qualifiers = EXPANDED_QUALIFIERS)
    fun railItemKeepsMinimumTouchTarget() {
        setAppContent()

        composeRule
            .onNodeWithText(context.getString(R.string.navigation_library))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = EXPANDED_QUALIFIERS)
    fun railItemIsFocusableAndActivatesWithEnterKey() {
        val navController = setAppContent()
        val dataItem =
            composeRule.onNode(
                hasClickAction() and hasText(context.getString(R.string.navigation_data)),
            )

        dataItem.requestFocus()
        dataItem.assertIsFocused()
        dataItem.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        val current = requireNotNull(navController.currentDestination)
        assertTrue(current.hasRoute<DataRoute>())
    }

    private fun setAppContent(): TestNavHostController {
        lateinit var navController: TestNavHostController
        composeRule.setContent {
            navController =
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            NdcShelfTheme {
                NdcShelfApp(
                    viewModel =
                        MainViewModel(
                            EmptyLibraryRepository,
                            dispatcher,
                            dispatcher,
                        ),
                    navController = navController,
                    onboardingStore = InMemoryOnboardingStore(completed = true),
                )
            }
        }
        composeRule.waitForIdle()
        return navController
    }
}

internal const val COMPACT_QUALIFIERS =
    "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav"
internal const val MEDIUM_QUALIFIERS =
    "w720dp-h1024dp-normal-notlong-notround-any-320dpi-keyshidden-nonav"
internal const val EXPANDED_QUALIFIERS =
    "w1280dp-h800dp-normal-notlong-notround-any-320dpi-keyshidden-nonav"

private object EmptyLibraryRepository : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryBook>> = flowOf(emptyList())

    override suspend fun addFromIsbn(rawIsbn: String): AddBookResult = AddBookResult.Failure(AddBookFailure.SAVE, rawIsbn)

    override suspend fun updateBook(
        copyId: String,
        draft: BookEditDraft,
    ): UpdateBookResult = UpdateBookResult.NotFound

    override suspend fun restoreBook(
        previous: LibraryBook,
        expectedCurrent: LibraryBook,
    ): Boolean = false

    override suspend fun deleteBook(copyId: String): DeleteBookResult = DeleteBookResult.NotFound

    override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult = RestoreDeletedBookResult.Failure

    override suspend fun previewImport(
        batch: LibraryImportBatch,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult = error("Not used in adaptive layout tests")

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = error("Not used in adaptive layout tests")
}
