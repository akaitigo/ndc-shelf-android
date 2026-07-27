package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.domain.export.LibraryExporter
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class LibraryCsvImporterTest {
    @Test
    fun `exported CSV round trip handles BOM CRLF quotes newlines and protection`() = runBlocking {
        val original = sampleBook(
            title = "=本, \"特装版\"\n第二行",
            location = "  +書斎\n本棚A",
        )
        val exported = LibraryExporter.export(listOf(original), LibraryExportFormat.CSV)

        val parsed = importer().parse(ByteArrayInputStream(exported)) as LibraryCsvParseResult.Valid
        val planned = planner().preview(
            parsed.batch,
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Valid

        assertEquals(listOf(original.copy(location = "+書斎\n本棚A")), planned.preview.additions)
        assertEquals(emptyList<ImportValidationError>(), parsed.warnings)
    }

    @Test
    fun `exported CSV round trip preserves apostrophes before formula prefixes`() = runBlocking {
        val original = sampleBook(
            title = "'=TITLE()",
            location = "  '@LOCATION",
        ).copy(
            primaryAuthor = "'+AUTHOR",
            publisher = "'-PUBLISHER",
            copyLabel = "'ordinary apostrophe",
        )
        val exported = LibraryExporter.export(listOf(original), LibraryExportFormat.CSV)

        val parsed = importer().parse(ByteArrayInputStream(exported)) as LibraryCsvParseResult.Valid
        val planned = planner().preview(
            parsed.batch,
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Valid

        assertEquals(listOf(original.copy(location = "'@LOCATION")), planned.preview.additions)
    }

    @Test
    fun `manually authored ordinary apostrophe is not treated as protection`() = runBlocking {
        val source = requiredHeader() +
            "\n'Book,'Author,9784820418078,NDL,PHYSICAL,'Shelf,READING,1"

        val result = parse(source) as LibraryCsvParseResult.Valid

        assertEquals("'Book", result.batch.records.single().title)
        assertEquals("'Author", result.batch.records.single().primaryAuthor)
        assertEquals("'Shelf", result.batch.records.single().location)
    }

    @Test
    fun `exported CSV round trip preserves manual source without ISBN`() = runBlocking {
        val manual = sampleBook("手動本", "未設定").copy(
            isbn13 = null,
            coverUrl = null,
            bibliographicSource = BibliographicSource.MANUAL,
        )
        val exported = LibraryExporter.export(listOf(manual), LibraryExportFormat.CSV)

        val parsed = importer().parse(exported.inputStream()) as LibraryCsvParseResult.Valid
        val planned = planner().preview(
            parsed.batch,
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Valid

        assertEquals(listOf(manual), planned.preview.additions)
    }

    @Test
    fun `columns map by name unknown columns warn and missing IDs are generated`() = runBlocking {
        val source = """title,isbn13,primaryAuthor,location,readingStatus,mediaType,classificationSource,addedAt,notes
            |本の題名,9784820418078,著者,書斎,READING,PHYSICAL,NDL,1700000000000,ignored
        """.trimMargin()

        val result = parse(source) as LibraryCsvParseResult.Valid
        val record = result.batch.records.single()

        assertEquals("copy:generated", record.copyId)
        assertEquals("work:isbn:9784820418078", record.workId)
        assertEquals("edition:isbn:9784820418078", record.editionId)
        assertTrue(result.warnings.any { it.field == "notes" && it.reason.contains("無視") })
    }

    @Test
    fun `missing required and duplicate headers are rejected`() = runBlocking {
        val missing = parse("title,isbn13\n本,9784820418078") as LibraryCsvParseResult.Invalid
        val duplicate = parse(
            "title,title,primaryAuthor,isbn13,classificationSource,mediaType,location," +
                "readingStatus,addedAt\n本,本,著者,9784820418078,NDL,PHYSICAL,棚,READING,1",
        ) as LibraryCsvParseResult.Invalid

        assertTrue(missing.errors.any { it.field == "primaryAuthor" && it.reason.contains("必須列") })
        assertTrue(duplicate.errors.any { it.reason.contains("構文") })
    }

    @Test
    fun `invalid numeric cell reports row and column`() = runBlocking {
        val source = requiredHeader() +
            "\n本,著者,9784820418078,NDL,PHYSICAL,棚,READING,not-a-number"

        val result = parse(source) as LibraryCsvParseResult.Invalid

        assertTrue(
            result.errors.any {
                it.recordNumber == 1 && it.field == "addedAt" && it.reason.contains("整数")
            },
        )
    }

    @Test
    fun `row width mismatch is rejected with its row number`() = runBlocking {
        val source = requiredHeader() +
            "\n本,著者,9784820418078,NDL,PHYSICAL,棚,READING,1,unexpected"

        val result = parse(source) as LibraryCsvParseResult.Invalid

        assertTrue(
            result.errors.any { it.recordNumber == 1 && it.reason.contains("列数") },
        )
    }

    @Test
    fun `row width errors stop parsing at the display limit`() = runBlocking {
        val widthErrors = List(100) { "too,few" }.joinToString("\n")
        val source = requiredHeader() + "\n" + widthErrors + "\n\"unterminated"

        val result = parse(source) as LibraryCsvParseResult.Invalid

        assertEquals(100, result.errors.size)
        assertTrue(result.errors.all { it.reason.contains("列数") })
        assertEquals(100, result.errors.last().recordNumber)
    }

    @Test
    fun `unterminated quoted cell is rejected as malformed CSV`() = runBlocking {
        val source = requiredHeader() +
            "\n\"unterminated,著者,9784820418078,NDL,PHYSICAL,棚,READING,1"

        val result = parse(source) as LibraryCsvParseResult.Invalid

        assertTrue(result.errors.any { it.reason.contains("構文") })
    }

    @Test
    fun `spreadsheet resaved fixture remains importable`() = runBlocking {
        val input = requireNotNull(javaClass.getResourceAsStream("/fixtures/libreoffice-edited.csv"))

        val parsed = importer().parse(input) as LibraryCsvParseResult.Valid
        val planned = planner().preview(
            parsed.batch,
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Valid
        val book = planned.preview.additions.single()

        assertEquals("表計算で編集した \"本\"\n改訂版", book.title)
        assertEquals("+移動先", book.location)
        assertEquals("メモ", parsed.warnings.single().field)
    }

    @Test
    fun `oversized record count and malformed UTF-8 are rejected`() = runBlocking {
        val oversized = LibraryCsvImporter(
            limits = LibraryImportLimits(maxSourceBytes = 8),
        ).parse(ByteArrayInputStream("123456789".toByteArray())) as LibraryCsvParseResult.Invalid
        val tooMany = LibraryCsvImporter(
            limits = LibraryImportLimits(maxRecords = 0),
        ).parse(
            ByteArrayInputStream(
                (requiredHeader() +
                    "\n本,著者,9784820418078,NDL,PHYSICAL,棚,READING,1").toByteArray(),
            ),
        ) as LibraryCsvParseResult.Invalid
        val malformed = importer().parse(
            ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)),
        ) as LibraryCsvParseResult.Invalid

        assertTrue(oversized.errors.any { it.reason.contains("8バイト") })
        assertTrue(tooMany.errors.any { it.reason.contains("0件以下") })
        assertTrue(malformed.errors.any { it.reason.contains("UTF-8") })
    }

    private suspend fun parse(source: String): LibraryCsvParseResult = importer().parse(
        ByteArrayInputStream(source.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun importer() = LibraryCsvImporter(newCopyId = { "copy:generated" })

    private fun planner() = LibraryImportPlanner(nowMillis = { 1_800_000_000_000L })

    private fun requiredHeader() =
        "title,primaryAuthor,isbn13,classificationSource,mediaType,location,readingStatus,addedAt"

    private fun sampleBook(
        title: String,
        location: String,
    ) = LibraryBook(
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
        location = location,
        readingStatus = ReadingStatus.READING,
        addedAt = 1_700_000_000_000L,
        copyLabel = "保存用",
    )
}
