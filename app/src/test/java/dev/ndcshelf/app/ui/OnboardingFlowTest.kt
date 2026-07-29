package dev.ndcshelf.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.ndcshelf.app.domain.model.InMemoryOnboardingStore
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.ui.navigation.OnboardingRoute
import dev.ndcshelf.app.ui.navigation.ScanRoute
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
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
class OnboardingFlowTest {
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
    fun firstLaunchShowsOnboardingAndSkipPersistsCompletion() {
        val store = InMemoryOnboardingStore(completed = false)
        setContent(store)

        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_welcome_title))
            .assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.onboarding_skip)).performClick()
        composeRule.waitForIdle()

        assertTrue(store.hasCompleted())
        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_welcome_title))
            .assertDoesNotExist()
    }

    @Test
    fun completedOnboardingIsNotShownOnLaunch() {
        setContent(InMemoryOnboardingStore(completed = true))
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_welcome_title))
            .assertDoesNotExist()
    }

    @Test
    fun cameraPageExplainsManualAlternativeBeforeAnyPermissionRequest() {
        val store = InMemoryOnboardingStore(completed = false)
        setContent(store)

        composeRule.onNodeWithText(context.getString(R.string.onboarding_next)).performClick()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_camera_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_camera_body))
            .assertIsDisplayed()
        assertFalse(store.hasCompleted())
    }

    @Test
    fun scanActionCompletesOnboardingAndOpensScanTab() {
        val store = InMemoryOnboardingStore(completed = false)
        lateinit var navController: TestNavHostController
        composeRule.setContent {
            navController =
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            NdcShelfTheme {
                NdcShelfApp(
                    viewModel = viewModel(),
                    navController = navController,
                    onboardingStore = store,
                )
            }
        }
        val next = context.getString(R.string.onboarding_next)
        repeat(3) {
            composeRule.onNodeWithText(next).performClick()
            composeRule.waitForIdle()
        }

        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_action_scan))
            .performClick()
        composeRule.waitForIdle()

        assertTrue(store.hasCompleted())
        val destination = requireNotNull(navController.currentDestination)
        assertTrue(destination.hasRoute<ScanRoute>())
    }

    @Test
    fun infoScreenCanReplayCompletedOnboarding() {
        val store = InMemoryOnboardingStore(completed = true)
        lateinit var navController: TestNavHostController
        composeRule.setContent {
            navController =
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            NdcShelfTheme {
                NdcShelfApp(
                    viewModel = viewModel(),
                    navController = navController,
                    onboardingStore = store,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.navigation_info)).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.info_replay_onboarding))
            .performClick()
        composeRule.waitForIdle()

        val destination = requireNotNull(navController.currentDestination)
        assertTrue(destination.hasRoute<OnboardingRoute>())
        composeRule
            .onNodeWithText(context.getString(R.string.onboarding_welcome_title))
            .assertIsDisplayed()
    }

    @Test
    fun libraryHelpDialogExplainsNdcLocationAndReadingStatus() {
        setContent(InMemoryOnboardingStore(completed = true))
        composeRule.waitForIdle()

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.library_help_button))
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.library_help_ndc)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.library_help_location)).assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.library_help_reading_status))
            .assertExists()
    }

    private fun setContent(store: InMemoryOnboardingStore) {
        composeRule.setContent {
            NdcShelfTheme {
                NdcShelfApp(viewModel = viewModel(), onboardingStore = store)
            }
        }
    }

    private fun viewModel(): MainViewModel = MainViewModel(FakeOnboardingRepository(), dispatcher, dispatcher)
}

private class FakeOnboardingRepository : LibraryRepository {
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
    ): ImportPreviewResult = error("Not used in onboarding tests")

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = error("Not used in onboarding tests")
}
