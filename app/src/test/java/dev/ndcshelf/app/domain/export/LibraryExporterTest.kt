package dev.ndcshelf.app.domain.export

import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LibraryExporterTest {
    @Test
    fun `JSON export preserves every field and escapes control characters`() {
        val output = LibraryExporter.export(
            books = listOf(sampleBook(title = "引用符\"と改行\nを含む本")),
            format = LibraryExportFormat.JSON,
            exportedAt = 1_722_345_678_901L,
        ).toString(StandardCharsets.UTF_8)

        assertTrue(output.contains("\"schemaVersion\": 1"))
        assertTrue(output.contains("\"exportedAt\": 1722345678901"))
        assertTrue(output.contains("\"bookCount\": 1"))
        assertTrue(output.contains("\"title\": \"引用符\\\"と改行\\nを含む本\""))
        assertTrue(output.contains("\"publisher\": null"))
        assertTrue(output.contains("\"classificationSource\": \"NDL\""))
        assertTrue(output.contains("\"readingStatus\": \"READING\""))
        assertFalse(output.startsWith("\uFEFF"))
    }

    @Test
    fun `CSV export uses UTF-8 BOM and protects spreadsheet formulas`() {
        val output = LibraryExporter.export(
            books = listOf(
                sampleBook(
                    title = "=HYPERLINK(\"https://example.invalid\")",
                    location = "  +cmd|' /C calc'!A0",
                ),
            ),
            format = LibraryExportFormat.CSV,
        ).toString(StandardCharsets.UTF_8)

        assertTrue(output.startsWith("\uFEFF"))
        assertTrue(output.contains("\"'=HYPERLINK(\"\"https://example.invalid\"\")\""))
        assertTrue(output.contains("\"'  +cmd|' /C calc'!A0\""))
        assertTrue(output.endsWith("\r\n"))
        assertEquals(3, output.split("\r\n").size)
    }

    @Test
    fun `CSV export escapes quotes commas and embedded newlines`() {
        val output = LibraryExporter.export(
            books = listOf(
                sampleBook(
                    title = "本, \"特装版\"",
                    location = "書斎\n本棚A",
                ),
            ),
            format = LibraryExportFormat.CSV,
        ).toString(StandardCharsets.UTF_8)

        assertTrue(output.contains("\"本, \"\"特装版\"\"\""))
        assertTrue(output.contains("\"書斎\n本棚A\""))
        assertFalse(output.replace("\r\n", "").contains('\r'))
    }

    @Test
    fun `empty exports remain valid documents`() {
        val json = LibraryExporter.export(
            books = emptyList(),
            format = LibraryExportFormat.JSON,
            exportedAt = 0L,
        ).toString(StandardCharsets.UTF_8)
        val csv = LibraryExporter.export(
            books = emptyList(),
            format = LibraryExportFormat.CSV,
        ).toString(StandardCharsets.UTF_8)

        assertTrue(json.contains("\"books\": []"))
        assertEquals(2, csv.split("\r\n").size)
    }

    private fun sampleBook(
        title: String,
        location: String = "書斎 / 本棚A",
    ) = LibraryBook(
        copyId = "copy-1",
        workId = "work-1",
        editionId = "edition-1",
        title = title,
        primaryAuthor = "山田太郎",
        isbn13 = "9784820418078",
        publisher = null,
        publishedYear = 2024,
        coverUrl = "https://example.invalid/cover.jpg",
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        classificationSource = ClassificationSource.NDL,
        mediaType = MediaType.PHYSICAL,
        location = location,
        readingStatus = ReadingStatus.READING,
        addedAt = 1_700_000_000_000L,
    )
}
