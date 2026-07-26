package dev.ndcshelf.app.release

import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.domain.export.LibraryExporter
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.ImportPreviewResult
import dev.ndcshelf.app.domain.importer.LibraryCsvImporter
import dev.ndcshelf.app.domain.importer.LibraryCsvParseResult
import dev.ndcshelf.app.domain.importer.LibraryImportPlanner
import dev.ndcshelf.app.domain.importer.LibraryJsonImporter
import dev.ndcshelf.app.domain.importer.LibraryJsonParseResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V02ReleaseFixtureTest {
    @Test
    fun anonymousFixturePassesJsonAndCsvRoundTrips() = runBlocking {
        val json = requireNotNull(javaClass.getResourceAsStream(FIXTURE_PATH)).use { input ->
            LibraryJsonImporter().parse(input) as LibraryJsonParseResult.Valid
        }
        val jsonBooks = plan(json)
        assertEquals(2, jsonBooks.size)
        assertTrue(jsonBooks.none { it.title.contains("実在") || it.location.contains("自宅") })

        val exportedJson = LibraryExporter.export(
            books = jsonBooks,
            format = LibraryExportFormat.JSON,
            exportedAt = json.exportedAt,
        )
        val reparsedJson = LibraryJsonImporter().parse(exportedJson.inputStream())
            as LibraryJsonParseResult.Valid
        assertEquals(jsonBooks, plan(reparsedJson))

        val exportedCsv = LibraryExporter.export(
            books = jsonBooks,
            format = LibraryExportFormat.CSV,
            exportedAt = json.exportedAt,
        )
        assertTrue(exportedCsv.toString(Charsets.UTF_8).contains("'=匿名サンプル図書A"))
        val csvParseResult = LibraryCsvImporter(
            newCopyId = { error("Fixture includes every copyId") },
        ).parse(exportedCsv.inputStream())
        assertTrue(csvParseResult.toString(), csvParseResult is LibraryCsvParseResult.Valid)
        val reparsedCsv = csvParseResult as LibraryCsvParseResult.Valid
        val csvBooks = plan(reparsedCsv)
        assertEquals(jsonBooks, csvBooks)
    }

    private fun plan(source: LibraryJsonParseResult.Valid) = plan(source.batch)

    private fun plan(source: LibraryCsvParseResult.Valid) = plan(source.batch)

    private fun plan(batch: dev.ndcshelf.app.domain.importer.LibraryImportBatch) =
        (LibraryImportPlanner(nowMillis = { NOW }).preview(
            batch = batch,
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Valid).preview.additions

    private companion object {
        const val FIXTURE_PATH = "/v0.2/anonymous-library-v1.json"
        const val NOW = 1_800_000_000_000L
    }
}
