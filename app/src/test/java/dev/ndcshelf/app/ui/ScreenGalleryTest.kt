package dev.ndcshelf.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ndcshelf.app.BookstoreUiState
import dev.ndcshelf.app.ManualRegistrationUiState
import dev.ndcshelf.app.ScanSessionUiState
import dev.ndcshelf.app.ScanUiState
import dev.ndcshelf.app.domain.model.BookSeries
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.SeriesMembership
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesVolume
import dev.ndcshelf.app.ui.screens.ScanScreen
import dev.ndcshelf.app.ui.screens.SeriesScreen
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * READMEと配布ページへ掲載する、スキャン・シリーズ画面のスクリーンショット回帰。
 * 主要画面（本棚・分類・オンボーディング）は[ScreenshotRegressionTest]が担当する。
 * 匿名fixtureだけを使い、実在ISBN・氏名・棚位置・通知内容を含めない。
 *
 * 掲載用のため、ロケールは日本語（`values-ja`）に固定する。
 * 英語・擬似ロケールの回帰は[ScreenshotRegressionTest]が担当する（docs/I18N.md）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "ja-rJP-w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class ScreenGalleryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scanLight() = capture("scan_light") { ScanFixture() }

    @Test
    fun scanDark() = capture("scan_dark", darkTheme = true) { ScanFixture() }

    @Test
    fun seriesLight() = capture("series_light") { SeriesFixture() }

    @Test
    fun seriesDark() = capture("series_dark", darkTheme = true) { SeriesFixture() }

    private fun capture(
        name: String,
        darkTheme: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            NdcShelfTheme(darkTheme = darkTheme) { content() }
        }
        composeRule.onRoot().captureRoboImage(
            "roborazzi/$name.png",
            roborazziOptions =
                RoborazziOptions(
                    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01F),
                ),
        )
    }
}

/** カメラ権限が無い状態。手入力・手動登録が代替経路として使えることを示す。 */
@Composable
private fun ScanFixture() {
    ScanScreen(
        scanState = ScanUiState.Idle,
        bookstoreState = BookstoreUiState.Idle,
        wishlist = emptyList(),
        scanSessions = emptyList(),
        scanSessionState = ScanSessionUiState.Idle,
        manualRegistrationState = ManualRegistrationUiState.Idle,
        onSubmitIsbn = {},
        onLookupBookstore = {},
        onCameraError = {},
        onBookstoreCameraError = {},
        onRetry = {},
        onRetryBookstore = {},
        onClearState = {},
        onClearBookstoreState = {},
        onAddDuplicateCopy = {},
        onAddManualBook = {},
        onClearManualRegistrationState = {},
        onChangePurchaseState = {},
        onSelectWishlistItem = {},
        onStartScanSession = {},
        onFinishScanSession = {},
        onUndoScanAttempt = {},
        onUndoScanSession = {},
        contentPadding = PaddingValues(),
    )
}

/** 所有巻・欠巻候補・読了状況が混在するシリーズ。 */
@Composable
private fun SeriesFixture() {
    SeriesScreen(
        series = listOf(DEMO_SERIES),
        selectedSeriesId = null,
        onSelectSeries = {},
        onOpenEdition = {},
        onOpenBookstore = {},
        contentPadding = PaddingValues(),
    )
}

private val DEMO_SERIES =
    SeriesOverview(
        series = BookSeries("series-demo", "匿名サンプル叢書", 1, 1_753_000_000_000),
        volumes =
            listOf(
                demoVolume("1", ownedEditionId = "edition-1", readCopies = 1),
                demoVolume("2", ownedEditionId = "edition-2"),
                demoVolume("3", isbn = "9784000000039", purchaseStatus = PurchaseStatus.WANTED),
                demoVolume("外伝", type = SeriesMembershipType.SIDE_STORY, ownedEditionId = "edition-4"),
            ),
    )

private fun demoVolume(
    label: String,
    type: SeriesMembershipType = SeriesMembershipType.MAIN_STORY,
    ownedEditionId: String? = null,
    isbn: String? = null,
    readCopies: Int = 0,
    purchaseStatus: PurchaseStatus? = null,
): SeriesVolume =
    SeriesVolume(
        membership =
            SeriesMembership(
                id = "membership-$label",
                seriesId = "series-demo",
                workId = "work-$label",
                workTitle = "匿名サンプル叢書 $label",
                primaryAuthor = "サンプル著者",
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
