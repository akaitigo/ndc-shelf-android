package dev.ndcshelf.app

import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.domain.export.LibraryExporter
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryImportBatch
import dev.ndcshelf.app.domain.importer.LibraryImportPlanner
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelImportTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `JSON is previewed with skip default and explicit update policy`() = runTest(dispatcher) {
        val existing = sampleBook(title = "旧題")
        val repository = FakeLibraryRepository(listOf(existing))
        val viewModel = viewModel(repository)
        val source = export(sampleBook(title = "新題"))

        viewModel.loadJsonImport(ByteArrayInputStream(source))

        val skipped = viewModel.importState.value as LibraryImportUiState.Preview
        assertEquals(ImportConflictPolicy.SKIP_EXISTING, skipped.conflictPolicy)
        assertEquals(1, skipped.skippedCount)
        assertEquals(0, skipped.changeCount)

        viewModel.selectImportConflictPolicy(ImportConflictPolicy.UPDATE_EXISTING)

        val updated = viewModel.importState.value as LibraryImportUiState.Preview
        assertEquals(ImportConflictPolicy.UPDATE_EXISTING, updated.conflictPolicy)
        assertEquals(1, updated.updatedCount)
        assertEquals(1, updated.changeCount)
    }

    @Test
    fun `confirmed preview reports applied counts`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(emptyList())
        val viewModel = viewModel(repository)
        viewModel.loadJsonImport(ByteArrayInputStream(export(sampleBook())))

        viewModel.confirmImport()

        val success = viewModel.importState.value as LibraryImportUiState.Success
        assertEquals(1, success.addedCount)
        assertEquals(0, success.updatedCount)
        assertEquals(0, success.skippedCount)
        assertEquals(1, repository.applyCalls)
    }

    @Test
    fun `future JSON schema becomes a visible validation error`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(emptyList())
        val viewModel = viewModel(repository)
        val source = export(sampleBook()).toString(Charsets.UTF_8)
            .replace("\"schemaVersion\": 2", "\"schemaVersion\": 999")
            .toByteArray()

        viewModel.loadJsonImport(ByteArrayInputStream(source))

        val invalid = viewModel.importState.value as LibraryImportUiState.Invalid
        assertTrue(invalid.errors.any { it.field == "schemaVersion" })
        assertEquals(0, repository.previewCalls)
    }

    @Test
    fun `CSV is previewed and unknown columns remain visible as warnings`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(emptyList())
        val viewModel = viewModel(repository)
        val source = """copyId,workId,editionId,title,primaryAuthor,isbn13,classificationSource,mediaType,location,readingStatus,addedAt,notes
            |copy-1,work-1,edition-1,本の題名,著者,9784820418078,NDL,PHYSICAL,本棚A,READING,1700000000000,ignored
        """.trimMargin().toByteArray()

        viewModel.loadCsvImport(ByteArrayInputStream(source))

        val preview = viewModel.importState.value as LibraryImportUiState.Preview
        assertEquals(1, preview.addedCount)
        assertTrue(preview.warnings.any { it.field == "notes" })
        assertEquals(1, repository.previewCalls)
    }

    @Test
    fun `stale preview is recalculated before another confirmation`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(emptyList()).apply {
            nextApplyResult = ImportApplyResult.StalePreview
        }
        val viewModel = viewModel(repository)
        viewModel.loadJsonImport(ByteArrayInputStream(export(sampleBook())))

        viewModel.confirmImport()

        val preview = viewModel.importState.value as LibraryImportUiState.Preview
        assertTrue(preview.staleRecalculated)
        assertEquals(2, repository.previewCalls)
        assertEquals(1, repository.applyCalls)
    }

    @Test
    fun `export remains in ViewModel state until result is consumed`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeLibraryRepository(listOf(sampleBook())))
        val collection = launch { viewModel.books.collect {} }
        val output = ByteArrayOutputStream()

        viewModel.exportLibrary(LibraryExportFormat.JSON, output)

        assertEquals(LibraryExportUiState.Success(1), viewModel.libraryExportState.value)
        assertTrue(output.toString(Charsets.UTF_8.name()).contains("本の題名"))
        viewModel.consumeLibraryExportResult()
        assertEquals(LibraryExportUiState.Idle, viewModel.libraryExportState.value)
        collection.cancel()
    }

    @Test
    fun `export write failure becomes visible state`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeLibraryRepository(listOf(sampleBook())))
        val output = object : OutputStream() {
            override fun write(value: Int) = throw java.io.IOException("disk full")
        }

        viewModel.exportLibrary(LibraryExportFormat.JSON, output)

        assertEquals(LibraryExportUiState.Error, viewModel.libraryExportState.value)
    }

    private fun viewModel(repository: LibraryRepository) = MainViewModel(
        repository = repository,
        importIoDispatcher = dispatcher,
        importComputationDispatcher = dispatcher,
    )

    private fun export(book: LibraryBook): ByteArray = LibraryExporter.export(
        books = listOf(book),
        format = LibraryExportFormat.JSON,
        exportedAt = 1_722_345_678_901L,
    )

    private fun sampleBook(title: String = "本の題名") = LibraryBook(
        copyId = "copy-1",
        workId = "work-1",
        editionId = "edition-1",
        title = title,
        primaryAuthor = "著者",
        isbn13 = "9784820418078",
        publisher = null,
        publishedYear = 2024,
        coverUrl = "https://ndlsearch.ndl.go.jp/thumbnail/9784820418078.jpg",
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        classificationSource = ClassificationSource.NDL,
        mediaType = MediaType.PHYSICAL,
        location = "本棚A",
        readingStatus = ReadingStatus.READING,
        addedAt = 1_700_000_000_000L,
    )

    private class FakeLibraryRepository(
        existingBooks: List<LibraryBook>,
    ) : LibraryRepository {
        private val books = MutableStateFlow(existingBooks)
        private val planner = LibraryImportPlanner(nowMillis = { 1_800_000_000_000L })
        var previewCalls = 0
        var applyCalls = 0
        var nextApplyResult: ImportApplyResult? = null

        override fun observeLibrary(): Flow<List<LibraryBook>> = books

        override suspend fun addFromIsbn(rawIsbn: String): AddBookResult =
            error("Not used")

        override suspend fun updateBook(copyId: String, draft: BookEditDraft): UpdateBookResult =
            error("Not used")

        override suspend fun restoreBook(
            previous: LibraryBook,
            expectedCurrent: LibraryBook,
        ): Boolean = error("Not used")

        override suspend fun deleteBook(copyId: String): DeleteBookResult = error("Not used")

        override suspend fun restoreDeletedBook(book: LibraryBook): RestoreDeletedBookResult =
            error("Not used")

        override suspend fun previewImport(
            batch: LibraryImportBatch,
            conflictPolicy: ImportConflictPolicy,
        ): ImportPreviewResult {
            previewCalls += 1
            return planner.preview(batch, books.value, conflictPolicy)
        }

        override suspend fun applyImport(preview: LibraryImportPreview): ImportApplyResult {
            applyCalls += 1
            nextApplyResult?.let { result ->
                nextApplyResult = null
                return result
            }
            books.value = preview.additions + preview.updates
            return ImportApplyResult.Applied(
                addedCount = preview.additions.size,
                updatedCount = preview.updates.size,
                skippedCount = preview.skippedCount,
            )
        }
    }
}
