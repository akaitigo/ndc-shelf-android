package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.export.LibraryExportFormat
import dev.ndcshelf.app.domain.export.LibraryExporter
import dev.ndcshelf.app.domain.model.BibliographicSource
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagColorRole
import dev.ndcshelf.app.domain.text.UiMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class LibraryJsonImporterTest {
    @Test
    fun `export import and re-export round trip preserves every field`() =
        runBlocking {
            val originalBook = sampleBook()
            val exported =
                LibraryExporter.export(
                    books = listOf(originalBook),
                    format = LibraryExportFormat.JSON,
                    exportedAt = EXPORTED_AT,
                )

            val parsed =
                LibraryJsonImporter().parse(ByteArrayInputStream(exported))
                    as LibraryJsonParseResult.Valid
            assertEquals(1, parsed.batch.records.size)
            val planned =
                LibraryImportPlanner(nowMillis = { NOW }).preview(
                    batch = parsed.batch,
                    existingBooks = emptyList(),
                    conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
                ) as ImportPreviewResult.Valid
            val reExported =
                LibraryExporter.export(
                    books = planned.preview.additions,
                    format = LibraryExportFormat.JSON,
                    exportedAt = parsed.exportedAt,
                )

            assertEquals(listOf(originalBook), planned.preview.additions)
            assertArrayEquals(exported, reExported)
        }

    @Test
    fun `schema three round trip preserves a manual book without ISBN`() =
        runBlocking {
            val manual =
                sampleBook().copy(
                    isbn13 = null,
                    coverUrl = null,
                    bibliographicSource = BibliographicSource.MANUAL,
                )
            val exported =
                LibraryExporter.export(
                    listOf(manual),
                    LibraryExportFormat.JSON,
                    EXPORTED_AT,
                )

            val parsed =
                LibraryJsonImporter().parse(exported.inputStream())
                    as LibraryJsonParseResult.Valid
            val planned =
                LibraryImportPlanner(nowMillis = { NOW }).preview(
                    parsed.batch,
                    emptyList(),
                    ImportConflictPolicy.SKIP_EXISTING,
                ) as ImportPreviewResult.Valid

            assertEquals(listOf(manual), planned.preview.additions)
        }

    @Test
    fun `UTF-8 BOM is accepted`() =
        runBlocking {
            val exported =
                LibraryExporter.export(
                    books = emptyList(),
                    format = LibraryExportFormat.JSON,
                    exportedAt = EXPORTED_AT,
                )
            val withBom = UTF8_BOM + exported

            val result = LibraryJsonImporter().parse(ByteArrayInputStream(withBom))

            assertEquals(0, (result as LibraryJsonParseResult.Valid).batch.records.size)
        }

    @Test
    fun `schema one import defaults the copy label without data loss`() =
        runBlocking {
            val source =
                validJson()
                    .replace("\"schemaVersion\": 4", "\"schemaVersion\": 1")
                    .replace("      \"copyLabel\": \"保存用\",\n", "")
                    .replace("      \"bibliographicSource\": \"NDL\",\n", "")

            val parsed = parse(source) as LibraryJsonParseResult.Valid
            val planned =
                LibraryImportPlanner(nowMillis = { NOW }).preview(
                    parsed.batch,
                    emptyList(),
                    ImportConflictPolicy.SKIP_EXISTING,
                ) as ImportPreviewResult.Valid

            // schema 1にはcopyLabelが無い。ロケール非依存の空文字（未設定）で補う。
            assertEquals(
                "",
                planned.preview.additions
                    .single()
                    .copyLabel,
            )
        }

    @Test
    fun `future schema version is rejected without exposing input`() =
        runBlocking {
            val source = validJson().replace("\"schemaVersion\": 4", "\"schemaVersion\": 999")

            val result = parse(source) as LibraryJsonParseResult.Invalid

            assertTrue(
                result.errors.any {
                    it.field == "schemaVersion" && it.reason == UiMessage(R.string.import_error_schema_too_new, 999L)
                },
            )
            assertTrue(result.errors.none { it.reason.args.any { arg -> arg == "本の題名" } })
        }

    @Test
    fun `book count mismatch unknown fields and missing optional fields are rejected`() =
        runBlocking {
            val source =
                validJson()
                    .replace("\"bookCount\": 1", "\"bookCount\": 2")
                    .replace("  \"books\":", "  \"unexpected\": true,\n  \"books\":")
                    .replace("      \"publisher\": null,\n", "")

            val result = parse(source) as LibraryJsonParseResult.Invalid

            assertTrue(result.errors.any { it.field == "bookCount" && it.reason.resId == R.string.import_error_book_count_mismatch })
            assertTrue(result.errors.any { it.field == "unexpected" && it.reason.resId == R.string.import_error_unknown_field })
            assertTrue(
                result.errors.any {
                    it.recordNumber == 1 && it.field == "publisher" && it.reason.resId == R.string.import_error_field_missing
                },
            )
        }

    @Test
    fun `wrong field types and malformed syntax are rejected with locations`() =
        runBlocking {
            val wrongType = validJson().replace("\"publishedYear\": 2024", "\"publishedYear\": \"2024\"")
            val typedResult = parse(wrongType) as LibraryJsonParseResult.Invalid
            val malformedResult = parse("{not-json") as LibraryJsonParseResult.Invalid

            assertTrue(
                typedResult.errors.any {
                    it.recordNumber == 1 && it.field == "publishedYear" && it.reason.resId == R.string.import_error_expect_integer
                },
            )
            assertTrue(malformedResult.errors.any { it.reason.resId == R.string.import_error_json_syntax })
        }

    @Test
    fun `unknown field names are truncated before display`() =
        runBlocking {
            val longField = "x".repeat(1_000)
            val source =
                validJson().replace(
                    "  \"books\":",
                    "  \"$longField\": true,\n  \"books\":",
                )

            val result = parse(source) as LibraryJsonParseResult.Invalid
            val field =
                result.errors
                    .first { it.reason.resId == R.string.import_error_unknown_field }
                    .field
                    .orEmpty()

            assertTrue(field.endsWith("…"))
            assertEquals(81, field.length)
        }

    @Test
    fun `oversized deeply nested and malformed UTF-8 inputs are rejected`() =
        runBlocking {
            val smallImporter = LibraryJsonImporter(LibraryImportLimits(maxSourceBytes = 8))
            val oversized =
                smallImporter.parse(ByteArrayInputStream("123456789".toByteArray()))
                    as LibraryJsonParseResult.Invalid
            val deeplyNested =
                LibraryJsonImporter().parse(
                    ByteArrayInputStream(("[".repeat(65) + "]".repeat(65)).toByteArray()),
                ) as LibraryJsonParseResult.Invalid
            val malformedUtf8 =
                LibraryJsonImporter().parse(
                    ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)),
                ) as LibraryJsonParseResult.Invalid

            assertTrue(oversized.errors.any { it.reason == UiMessage(R.string.import_error_file_too_large, 8L) })
            assertTrue(deeplyNested.errors.any { it.reason == UiMessage(R.string.import_error_json_nesting, 64) })
            assertTrue(malformedUtf8.errors.any { it.reason.resId == R.string.import_error_json_encoding })
        }

    @Test
    fun `unknown enum is rejected by the shared import planner`() =
        runBlocking {
            val source =
                validJson().replace(
                    "\"readingStatus\": \"READING\"",
                    "\"readingStatus\": \"FUTURE_VALUE\"",
                )
            val parsed = parse(source) as LibraryJsonParseResult.Valid

            val result =
                LibraryImportPlanner(nowMillis = { NOW }).preview(
                    parsed.batch,
                    existingBooks = emptyList(),
                    conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
                ) as ImportPreviewResult.Invalid

            assertTrue(result.errors.any { it.recordNumber == 1 && it.field == "readingStatus" })
        }

    @Test
    fun `schema four tags round trip through importer and planner`() =
        runBlocking {
            val exported =
                LibraryExporter
                    .export(
                        books = listOf(sampleBook()),
                        format = LibraryExportFormat.JSON,
                        exportedAt = EXPORTED_AT,
                        tags =
                            listOf(
                                Tag("tag-1", "SF", TagColorRole.BLUE, 1, 1),
                                Tag("tag-2", "積読", TagColorRole.GRAY, 1, 1),
                            ),
                        tagNamesByWorkId = mapOf("work-1" to listOf("SF", "積読")),
                    ).toString(StandardCharsets.UTF_8)

            val parsed = parse(exported) as LibraryJsonParseResult.Valid
            assertEquals(2, parsed.batch.tags.size)
            val planned =
                LibraryImportPlanner(nowMillis = { NOW }).preview(
                    parsed.batch,
                    emptyList(),
                    ImportConflictPolicy.SKIP_EXISTING,
                ) as ImportPreviewResult.Valid

            assertEquals(
                listOf(
                    ImportTagDefinition("SF", TagColorRole.BLUE),
                    ImportTagDefinition("積読", TagColorRole.GRAY),
                ),
                planned.preview.tagDefinitions,
            )
            assertEquals(
                mapOf("copy-1" to listOf("SF", "積読")),
                planned.preview.tagNamesByCopyId,
            )
        }

    @Test
    fun `undefined tag names and invalid definitions are rejected`() =
        runBlocking {
            // 蔵書側（6スペースインデント）のtagsだけを置き換える。
            val undefinedTag =
                validJson().replace("      \"tags\": [],", "      \"tags\": [\"未定義タグ\"],")
            val parsedUndefined = parse(undefinedTag) as LibraryJsonParseResult.Valid
            val undefinedResult =
                LibraryImportPlanner(nowMillis = { NOW }).preview(
                    parsedUndefined.batch,
                    emptyList(),
                    ImportConflictPolicy.SKIP_EXISTING,
                ) as ImportPreviewResult.Invalid
            assertTrue(
                undefinedResult.errors.any {
                    it.field == "tags" && it.reason.resId == R.string.import_error_tag_undefined
                },
            )

            val badColor =
                parse(
                    validJson().replaceFirst(
                        "  \"tags\": [],",
                        "  \"tags\": [{\"name\": \"SF\", \"colorRole\": \"NEON\"}],",
                    ),
                ) as LibraryJsonParseResult.Valid
            val badColorResult =
                LibraryImportPlanner(nowMillis = { NOW }).preview(
                    badColor.batch,
                    emptyList(),
                    ImportConflictPolicy.SKIP_EXISTING,
                ) as ImportPreviewResult.Invalid
            assertTrue(badColorResult.errors.any { it.field == "tags.colorRole" })
        }

    private suspend fun parse(source: String): LibraryJsonParseResult =
        LibraryJsonImporter().parse(
            ByteArrayInputStream(source.toByteArray(StandardCharsets.UTF_8)),
        )

    private fun validJson(): String =
        LibraryExporter
            .export(
                books = listOf(sampleBook()),
                format = LibraryExportFormat.JSON,
                exportedAt = EXPORTED_AT,
            ).toString(StandardCharsets.UTF_8)

    private fun sampleBook() =
        LibraryBook(
            copyId = "copy-1",
            workId = "work-1",
            editionId = "edition-1",
            title = "本の題名 \"特装版\"\n第二行",
            primaryAuthor = "著者",
            isbn13 = "9784820418078",
            publisher = null,
            publishedYear = 2024,
            coverUrl = "https://ndlsearch.ndl.go.jp/thumbnail/9784820418078.jpg",
            ndcCode = "014.45",
            ndcEdition = "NDC10",
            classificationSource = ClassificationSource.NDL,
            mediaType = MediaType.PHYSICAL,
            location = "書斎 / 本棚A",
            readingStatus = ReadingStatus.READING,
            addedAt = 1_700_000_000_000L,
            copyLabel = "保存用",
        )

    private companion object {
        const val EXPORTED_AT = 1_722_345_678_901L
        const val NOW = 1_800_000_000_000L
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
