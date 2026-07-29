package dev.ndcshelf.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ndcshelf.app.BookDeleteUiState
import dev.ndcshelf.app.BookEditUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.screens.InsightsScreen
import dev.ndcshelf.app.ui.screens.LibraryScreen
import dev.ndcshelf.app.ui.screens.OnboardingScreen
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 主要画面のライト・ダーク・大文字スクリーンショット回帰テスト。
 * 匿名fixtureだけを使用し、実在ISBN・氏名・棚位置を含めない。
 * golden画像は app/roborazzi/ にコミットし、CIの verifyRoborazziDebug が差分検出する。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class ScreenshotRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun libraryLight() = captureScreen("library_light") { LibraryFixture() }

    @Test
    fun libraryDark() = captureScreen("library_dark", darkTheme = true) { LibraryFixture() }

    @Test
    fun libraryLargeFont() = captureScreen("library_large_font", fontScale = 1.5f) { LibraryFixture() }

    @Test
    fun insightsLight() = captureScreen("insights_light") { InsightsFixture() }

    @Test
    fun insightsDark() = captureScreen("insights_dark", darkTheme = true) { InsightsFixture() }

    @Test
    fun onboardingLight() = captureScreen("onboarding_light") { OnboardingFixture() }

    @Test
    fun onboardingDark() = captureScreen("onboarding_dark", darkTheme = true) { OnboardingFixture() }

    private fun captureScreen(
        name: String,
        darkTheme: Boolean = false,
        fontScale: Float = 1.0f,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                NdcShelfTheme(darkTheme = darkTheme) {
                    content()
                }
            }
        }
        composeRule.onRoot().captureRoboImage(
            "$OUTPUT_DIRECTORY/$name.png",
            roborazziOptions =
                RoborazziOptions(
                    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01F),
                ),
        )
    }

    private companion object {
        const val OUTPUT_DIRECTORY = "roborazzi"
    }
}

@Composable
private fun LibraryFixture() {
    LibraryScreen(
        books = anonymousBooks(),
        libraryStats = LibraryStats(totalCount = 3, classifiedCount = 3, readingCount = 1),
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

@Composable
private fun InsightsFixture() {
    InsightsScreen(books = anonymousBooks(), contentPadding = PaddingValues())
}

@Composable
private fun OnboardingFixture() {
    OnboardingScreen(
        onStartScan = {},
        onManualEntry = {},
        onImport = {},
        onSkip = {},
        contentPadding = PaddingValues(),
    )
}

private fun anonymousBooks(): List<LibraryBook> =
    listOf(
        anonymousBook("copy-1", "匿名サンプル図書A", "サンプル著者A", "007.6", ReadingStatus.READING),
        anonymousBook("copy-2", "匿名サンプル図書B", "サンプル著者B", "913.6", ReadingStatus.UNREAD),
        anonymousBook("copy-3", "匿名サンプル図書C", "サンプル著者C", "410.1", ReadingStatus.READ),
    )

private fun anonymousBook(
    copyId: String,
    title: String,
    author: String,
    ndc: String,
    status: ReadingStatus,
): LibraryBook =
    LibraryBook(
        copyId = copyId,
        workId = "work-$copyId",
        editionId = "edition-$copyId",
        title = title,
        primaryAuthor = author,
        isbn13 = null,
        publisher = "匿名出版社",
        publishedYear = 2026,
        coverUrl = null,
        ndcCode = ndc,
        ndcEdition = "NDC10",
        classificationSource = ClassificationSource.MANUAL,
        mediaType = MediaType.PHYSICAL,
        location = "サンプル書斎",
        readingStatus = status,
        addedAt = 1_753_000_000_000,
        bibliographicSource = BibliographicSource.MANUAL,
    )
