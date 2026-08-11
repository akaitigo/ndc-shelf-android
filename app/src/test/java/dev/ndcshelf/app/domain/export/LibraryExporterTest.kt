package dev.ndcshelf.app.domain.export

import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagColorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class LibraryExporterTest {
    @Test
    fun `JSON export preserves every field and escapes control characters`() {
        val output =
            LibraryExporter
                .export(
                    books = listOf(sampleBook(title = "引用符\"と改行\nを含む本")),
                    format = LibraryExportFormat.JSON,
                    exportedAt = 1_722_345_678_901L,
                ).toString(StandardCharsets.UTF_8)

        assertTrue(output.contains("\"schemaVersion\": 4"))
        assertTrue(output.contains("\"tags\": [],"))
        assertTrue(output.contains("\"bibliographicSource\": \"NDL\""))
        assertTrue(output.contains("\"exportedAt\": 1722345678901"))
        assertTrue(output.contains("\"bookCount\": 1"))
        assertTrue(output.contains("\"title\": \"引用符\\\"と改行\\nを含む本\""))
        assertTrue(output.contains("\"publisher\": null"))
        assertTrue(output.contains("\"classificationSource\": \"NDL\""))
        assertTrue(output.contains("\"readingStatus\": \"READING\""))
        // 未設定の所蔵ラベルは空文字でexportする（表示時にlocalizeするため）。
        assertTrue(output.contains("\"copyLabel\": \"\""))
        assertFalse(output.startsWith("\uFEFF"))
    }

    @Test
    fun `JSON export writes tag definitions and per-book tag names`() {
        val output =
            LibraryExporter
                .export(
                    books = listOf(sampleBook(title = "\u30BF\u30B0\u4ED8\u304D\u306E\u672C")),
                    format = LibraryExportFormat.JSON,
                    exportedAt = 1L,
                    tags =
                        listOf(
                            Tag("tag-2", "\u7A4D\u8AAD", TagColorRole.BLUE, 1, 1),
                            Tag("tag-1", "SF \"\u5F15\u7528\"", TagColorRole.RED, 1, 1),
                        ),
                    tagNamesByWorkId = mapOf("work-1" to listOf("\u7A4D\u8AAD", "SF \"\u5F15\u7528\"")),
                ).toString(StandardCharsets.UTF_8)

        // \u30BF\u30B0\u5B9A\u7FA9\u306F\u540D\u524D\u9806\u3067\u3001\u5185\u90E8ID\u3092\u542B\u3081\u306A\u3044\u3002
        assertTrue(output.contains("\"name\": \"SF \\\"\u5F15\u7528\\\"\""))
        assertTrue(output.contains("\"colorRole\": \"RED\""))
        assertTrue(output.contains("\"colorRole\": \"BLUE\""))
        assertFalse(output.contains("tag-1"))
        // \u8535\u66F8\u5074\u306F\u30BF\u30B0\u540D\u306E\u914D\u5217\uFF08\u540D\u524D\u9806\uFF09\u3002
        assertTrue(output.contains("\"tags\": [\"SF \\\"\u5F15\u7528\\\"\", \"\u7A4D\u8AAD\"],"))
    }

    @Test
    fun `CSV export keeps the 18 columns without tags`() {
        val output =
            LibraryExporter
                .export(
                    books = listOf(sampleBook(title = "\u30BF\u30B0\u4ED8\u304D\u306E\u672C")),
                    format = LibraryExportFormat.CSV,
                    tags = listOf(Tag("tag-1", "\u7A4D\u8AAD", TagColorRole.BLUE, 1, 1)),
                    tagNamesByWorkId = mapOf("work-1" to listOf("\u7A4D\u8AAD")),
                ).toString(StandardCharsets.UTF_8)

        assertFalse(output.contains("\u7A4D\u8AAD"))
        assertEquals(
            18,
            output
                .split("\r\n")
                .first()
                .removePrefix("\uFEFF")
                .split("\",\"")
                .size,
        )
    }

    @Test
    fun `CSV export uses UTF-8 BOM and protects spreadsheet formulas`() {
        val output =
            LibraryExporter
                .export(
                    books =
                        listOf(
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
    fun `CSV export escapes leading apostrophe for lossless import`() {
        val output =
            LibraryExporter
                .export(
                    books = listOf(sampleBook(title = "'=literal")),
                    format = LibraryExportFormat.CSV,
                ).toString(StandardCharsets.UTF_8)

        assertTrue(output.contains("\"''=literal\""))
    }

    @Test
    fun `CSV export escapes quotes commas and embedded newlines`() {
        val output =
            LibraryExporter
                .export(
                    books =
                        listOf(
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
        val json =
            LibraryExporter
                .export(
                    books = emptyList(),
                    format = LibraryExportFormat.JSON,
                    exportedAt = 0L,
                ).toString(StandardCharsets.UTF_8)
        val csv =
            LibraryExporter
                .export(
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
