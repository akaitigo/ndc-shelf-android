package dev.ndcshelf.app.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ndcshelf.app.MainViewModel
import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.InMemoryOnboardingStore
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import dev.ndcshelf.app.ui.theme.NdcShelfTheme
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * READMEへ掲載する操作デモのフレームを、実アプリのComposeを動かして記録する。
 * 匿名fixtureだけを使い、実在ISBN・氏名・棚位置を含めない。
 *
 * 記録は明示実行のみ（`-Dndcshelf.recordDemo=true`）とし、通常のCIでは
 * スキップしてスクリーンショット回帰と実行時間を汚さない。
 * フレームは `tools/build_demo_gif.py` が `docs/images/demo.gif` へ合成する。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "ja-rJP-w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class DemoRecordingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var frame = 0

    @Test
    fun recordsReadmeDemoFrames() {
        if (System.getProperty(RECORD_PROPERTY) != "true") return

        // ViewModelはComposable外で生成する（lint: ViewModelConstructorInComposable）。
        val viewModel =
            MainViewModel(
                DemoLibraryRepository(),
                Dispatchers.Unconfined,
                Dispatchers.Unconfined,
            )
        composeRule.setContent {
            NdcShelfTheme {
                NdcShelfApp(
                    viewModel = viewModel,
                    onboardingStore = InMemoryOnboardingStore(completed = false),
                )
            }
        }

        // 1. オンボーディング: 価値説明 → カメラは任意 → 送信範囲 → 3導線
        capture()
        repeat(3) {
            composeRule.onNodeWithText(context.getString(R.string.onboarding_next)).performClick()
            composeRule.waitForIdle()
            capture()
        }

        // 2. 本棚へ
        composeRule.onNodeWithText(context.getString(R.string.onboarding_skip)).performClick()
        composeRule.waitForIdle()
        capture()

        // 3. 自然言語で絞り込み、解釈チップが出る
        composeRule
            .onNodeWithText(context.getString(R.string.library_search_placeholder))
            .performTextInput(DEMO_QUERY)
        composeRule.waitForIdle()
        capture()

        // 4. 分類タブ: NDC分布と読書傾向
        composeRule.onNodeWithText(context.getString(R.string.navigation_insights)).performClick()
        composeRule.waitForIdle()
        capture()

        // 5. データタブ: 移行・バックアップ・プライバシー導線
        composeRule.onNodeWithText(context.getString(R.string.navigation_data)).performClick()
        composeRule.waitForIdle()
        capture()

        // 6. 本棚へ戻る
        composeRule.onNodeWithText(context.getString(R.string.navigation_library)).performClick()
        composeRule.waitForIdle()
        capture()
    }

    private fun capture() {
        // 蔵書一覧はdebounce付きflowで供給されるため、描画前に時間を進める。
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MILLIS))
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("$OUTPUT_DIRECTORY/frame-%02d.png".format(frame))
        frame += 1
    }

    private companion object {
        const val RECORD_PROPERTY = "ndcshelf.recordDemo"
        const val OUTPUT_DIRECTORY = "build/demo-frames"
        const val DEMO_QUERY = "未読の自然科学"
        const val SETTLE_MILLIS = 500L
    }
}

/** READMEデモ用の匿名蔵書。実在ISBN・氏名・棚位置を含めない。 */
private class DemoLibraryRepository : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryBook>> = flowOf(DEMO_BOOKS)

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
    ): ImportPreviewResult = error("Not used in the demo recording")

    override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult = error("Not used in the demo recording")
}

private val DEMO_BOOKS =
    listOf(
        demoBook("copy-1", "匿名サンプル図書A", "サンプル著者A", "007.6", ReadingStatus.READING),
        demoBook("copy-2", "匿名サンプル図書B", "サンプル著者B", "913.6", ReadingStatus.UNREAD),
        demoBook("copy-3", "匿名サンプル図書C", "サンプル著者C", "410.1", ReadingStatus.UNREAD),
        demoBook("copy-4", "匿名サンプル図書D", "サンプル著者D", "469.2", ReadingStatus.READ),
    )

private fun demoBook(
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
