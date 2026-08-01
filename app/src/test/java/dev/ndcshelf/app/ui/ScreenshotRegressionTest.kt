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
import dev.ndcshelf.app.InsightsUiState
import dev.ndcshelf.app.LocationMutationUiState
import dev.ndcshelf.app.domain.insights.InsightsMonth
import dev.ndcshelf.app.domain.insights.LibraryInsightsCalculator
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibraryStats
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.ui.adaptive.AdaptiveLayout
import dev.ndcshelf.app.ui.adaptive.AdaptiveNavigationScaffold
import dev.ndcshelf.app.ui.navigation.TopLevelDestination
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
    fun libraryFontScale200() = captureScreen("library_font_scale_200", fontScale = 2.0f) { LibraryFixture() }

    @Test
    fun insightsFontScale200() = captureScreen("insights_font_scale_200", fontScale = 2.0f) { InsightsFixture() }

    @Test
    fun onboardingFontScale200() = captureScreen("onboarding_font_scale_200", fontScale = 2.0f) { OnboardingFixture() }

    @Test
    fun insightsLight() = captureScreen("insights_light") { InsightsFixture() }

    @Test
    fun insightsDark() = captureScreen("insights_dark", darkTheme = true) { InsightsFixture() }

    @Test
    fun onboardingLight() = captureScreen("onboarding_light") { OnboardingFixture() }

    @Test
    fun onboardingDark() = captureScreen("onboarding_dark", darkTheme = true) { OnboardingFixture() }

    /**
     * medium幅（NavigationRail + 1ペイン + 最大幅720dp）。
     * 代表サイズは docs/ADAPTIVE_LAYOUT.md の表に対応する。
     */
    @Test
    @Config(qualifiers = "w720dp-h1024dp-normal-notlong-notround-any-320dpi-keyshidden-nonav")
    fun libraryMediumRail() =
        captureScreen("library_medium_rail") {
            AdaptiveShellFixture(AdaptiveLayout.Medium) { LibraryFixture() }
        }

    /** expanded幅（NavigationRail + list-detail 2ペイン。詳細を選択済みの状態）。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-normal-notlong-notround-any-320dpi-keyshidden-nonav")
    fun libraryExpandedTwoPane() =
        captureScreen("library_expanded_two_pane") {
            AdaptiveShellFixture(AdaptiveLayout.Expanded) {
                LibraryFixture(twoPane = true, selectedEditionId = "edition-copy-1")
            }
        }

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

/**
 * 大画面のナビゲーション（NavigationRail）と最大幅制約を含めて撮影するためのシェル。
 * サイズクラスは実測ではなく明示指定し、qualifiersの解釈差でgoldenが揺れないようにする。
 */
@Composable
private fun AdaptiveShellFixture(
    layout: AdaptiveLayout,
    content: @Composable () -> Unit,
) {
    AdaptiveNavigationScaffold(
        isSelected = { it == TopLevelDestination.LIBRARY },
        onSelectDestination = {},
        layoutOverride = layout,
    ) { _, _ -> content() }
}

@Composable
private fun LibraryFixture(
    twoPane: Boolean = false,
    selectedEditionId: String? = null,
) {
    LibraryScreen(
        books = anonymousBooks(),
        searchCriteria =
            selectedEditionId?.let { LibrarySearchCriteria(selectedEditionId = it) },
        libraryStats = LibraryStats(totalCount = 3, classifiedCount = 3, readingCount = 1),
        twoPane = twoPane,
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
    val books = anonymousBooks()
    val insights =
        LibraryInsightsCalculator().calculate(
            books = books,
            sessions = emptyList(),
            excludedCopyIds = emptySet(),
            nowMillis = 1_753_000_000_000,
            currentMonth = InsightsMonth(2026, 7),
            rediscoverySeed = 42L,
        )
    InsightsScreen(
        state = InsightsUiState.Ready(books = books, insights = insights),
        onExcludeBook = {},
        onResetExclusions = {},
        contentPadding = PaddingValues(),
    )
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
