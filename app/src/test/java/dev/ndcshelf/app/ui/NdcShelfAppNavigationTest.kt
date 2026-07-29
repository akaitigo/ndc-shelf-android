package dev.ndcshelf.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.ui.navigation.DataRoute
import dev.ndcshelf.app.ui.navigation.LibraryRoute
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NdcShelfAppNavigationTest {
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
    fun tabClickNavigatesToDataAndBackReturnsToLibrary() {
        lateinit var navController: TestNavHostController
        composeRule.setContent {
            navController =
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            NdcShelfTheme {
                NdcShelfApp(viewModel = viewModel(), navController = navController)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.navigation_data)).performClick()
        composeRule.waitForIdle()

        val current = requireNotNull(navController.currentDestination)
        assertTrue(current.hasRoute<DataRoute>())
        composeRule
            .onNodeWithText(context.getString(R.string.data_management_title))
            .assertIsDisplayed()

        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()

        val afterBack = requireNotNull(navController.currentDestination)
        assertTrue(afterBack.hasRoute<LibraryRoute>())
    }

    @Test
    fun reselectingSameTabDoesNotStackDuplicateEntries() {
        lateinit var navController: TestNavHostController
        composeRule.setContent {
            navController =
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            NdcShelfTheme {
                NdcShelfApp(viewModel = viewModel(), navController = navController)
            }
        }
        val dataLabel = context.getString(R.string.navigation_data)

        composeRule.onNodeWithText(dataLabel).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(dataLabel).performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()

        val afterBack = requireNotNull(navController.currentDestination)
        assertTrue(afterBack.hasRoute<LibraryRoute>())
    }

    @Test
    fun selectedTabSurvivesStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            NdcShelfTheme {
                NdcShelfApp(viewModel = viewModel())
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.navigation_data)).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.data_management_title))
            .assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.data_management_title))
            .assertIsDisplayed()
    }

    @Test
    fun deepLinkToDeletedEditionClearsSelectionWithoutCrash() {
        val repository = FakeNavigationRepository()
        val viewModel = MainViewModel(repository, dispatcher, dispatcher)
        var handled = false
        composeRule.setContent {
            var requested by remember { mutableStateOf<String?>("edition-missing") }
            NdcShelfTheme {
                NdcShelfApp(
                    viewModel = viewModel,
                    requestedEditionId = requested,
                    onBookDetailRequestHandled = {
                        handled = true
                        requested = null
                    },
                )
            }
        }
        composeRule.waitForIdle()

        // 削除済み・無効IDへのdeep linkは選択を残さず安全に処理される
        assertTrue(handled)
        assertEquals(null, viewModel.librarySearchCriteria.value.selectedEditionId)
        composeRule.onNodeWithText(context.getString(R.string.navigation_library)).assertIsDisplayed()
    }

    private fun viewModel(): MainViewModel = MainViewModel(FakeNavigationRepository(), dispatcher, dispatcher)
}

private class FakeNavigationRepository : LibraryRepository {
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
    ): ImportPreviewResult = error("Not used in navigation tests")

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = error("Not used in navigation tests")
}
