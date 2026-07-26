package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.domain.export.LibraryExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.DuplicateHeaderMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

sealed interface LibraryCsvParseResult {
    data class Valid(
        val batch: LibraryImportBatch,
        val warnings: List<ImportValidationError>,
    ) : LibraryCsvParseResult

    data class Invalid(val errors: List<ImportValidationError>) : LibraryCsvParseResult
}

class LibraryCsvImporter(
    private val limits: LibraryImportLimits = LibraryImportLimits(),
    private val newCopyId: () -> String = { "copy:${UUID.randomUUID()}" },
) {
    suspend fun parse(input: InputStream): LibraryCsvParseResult {
        val bytes = try {
            readLimited(input)
        } catch (_: SourceTooLargeException) {
            return invalid("入力ファイルは${limits.maxSourceBytes}バイト以下にしてください")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return invalid("CSVファイルを読み込めませんでした")
        }

        val source = try {
            decodeUtf8(bytes)
        } catch (_: Exception) {
            return invalid("CSVファイルはUTF-8で保存してください")
        }
        currentCoroutineContext().ensureActive()
        if (source.isEmpty()) return invalid("ヘッダー行がありません")

        return try {
            parseSource(source, bytes.size.toLong())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            invalid("CSVの構文が正しくありません")
        }
    }

    private suspend fun parseSource(source: String, sourceSizeBytes: Long): LibraryCsvParseResult {
        val format = CSVFormat.RFC4180.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .setAllowMissingColumnNames(false)
            .setIgnoreEmptyLines(false)
            .build()
        val parser = format.parse(StringReader(source))
        parser.use {
            val headers = parser.headerNames
            val headerErrors = validateHeaders(headers)
            if (headerErrors.errors.isNotEmpty()) {
                return LibraryCsvParseResult.Invalid(headerErrors.errors)
            }

            val records = mutableListOf<UnvalidatedLibraryBook>()
            for (record in parser) {
                currentCoroutineContext().ensureActive()
                if (records.size >= limits.maxRecords) {
                    return invalid("蔵書件数は${limits.maxRecords}件以下にしてください")
                }
                val row = record.recordNumber.toInt()
                if (record.size() != headers.size) {
                    headerErrors.errors.addCapped(
                        ImportValidationError(row, null, "列数がヘッダーと一致しません"),
                    )
                    continue
                }
                val isbn = record.value("isbn13")
                records += UnvalidatedLibraryBook(
                    copyId = record.optionalId("copyId") ?: newCopyId(),
                    workId = record.optionalId("workId") ?: isbn.generatedId("work"),
                    editionId = record.optionalId("editionId") ?: isbn.generatedId("edition"),
                    title = record.value("title"),
                    primaryAuthor = record.value("primaryAuthor"),
                    isbn13 = isbn,
                    publisher = record.value("publisher"),
                    publishedYear = record.longValue("publishedYear", row, headerErrors.errors),
                    coverUrl = record.value("coverUrl"),
                    ndcCode = record.value("ndcCode"),
                    ndcEdition = record.value("ndcEdition"),
                    classificationSource = record.value("classificationSource"),
                    mediaType = record.value("mediaType"),
                    location = record.value("location"),
                    readingStatus = record.value("readingStatus"),
                    addedAt = record.longValue("addedAt", row, headerErrors.errors),
                )
                if (headerErrors.errors.size >= MAX_ERRORS) break
            }
            if (headerErrors.errors.isNotEmpty()) {
                return LibraryCsvParseResult.Invalid(headerErrors.errors)
            }
            return LibraryCsvParseResult.Valid(
                batch = LibraryImportBatch(sourceSizeBytes, records),
                warnings = headerErrors.warnings,
            )
        }
    }

    private fun validateHeaders(headers: List<String>): HeaderValidation {
        val errors = mutableListOf<ImportValidationError>()
        val warnings = mutableListOf<ImportValidationError>()
        REQUIRED_COLUMNS.filterNot(headers::contains).forEach { column ->
            errors.addCapped(ImportValidationError(null, column, "必須列がありません"))
        }
        (headers - LibraryExporter.CSV_COLUMNS.toSet()).forEach { column ->
            warnings.addCapped(
                ImportValidationError(null, column.safeForError(), "未知の列は無視されます"),
            )
        }
        return HeaderValidation(errors, warnings)
    }

    private fun org.apache.commons.csv.CSVRecord.optionalId(column: String): String? =
        if (isMapped(column)) value(column)?.takeIf(String::isNotBlank) else null

    private fun org.apache.commons.csv.CSVRecord.value(column: String): String? =
        if (isMapped(column)) get(column).restoreSpreadsheetProtection() else null

    private fun org.apache.commons.csv.CSVRecord.longValue(
        column: String,
        row: Int,
        errors: MutableList<ImportValidationError>,
    ): Long? {
        val value = value(column)?.takeIf(String::isNotBlank) ?: return null
        return value.toLongOrNull() ?: run {
            errors.addCapped(ImportValidationError(row, column, "整数として指定してください"))
            null
        }
    }

    private fun String.restoreSpreadsheetProtection(): String {
        if (!startsWith('\'')) return this
        val candidate = drop(1)
        return if (candidate.firstOrNull { !it.isWhitespace() } in FORMULA_PREFIXES) candidate else this
    }

    private fun String?.generatedId(kind: String): String? {
        val normalized = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return "$kind:isbn:${normalized.filter(Char::isDigit)}"
    }

    private suspend fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > limits.maxSourceBytes) throw SourceTooLargeException()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val start = if (bytes.startsWithUtf8Bom()) UTF8_BOM.size else 0
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
    }

    private fun ByteArray.startsWithUtf8Bom(): Boolean =
        size >= UTF8_BOM.size && UTF8_BOM.indices.all { this[it] == UTF8_BOM[it] }

    private fun String.safeForError(): String = if (length <= MAX_ERROR_FIELD_LENGTH) {
        this
    } else {
        take(MAX_ERROR_FIELD_LENGTH) + "…"
    }

    private fun MutableList<ImportValidationError>.addCapped(error: ImportValidationError) {
        if (size < MAX_ERRORS) add(error)
    }

    private fun invalid(reason: String) = LibraryCsvParseResult.Invalid(
        listOf(ImportValidationError(null, null, reason)),
    )

    private data class HeaderValidation(
        val errors: MutableList<ImportValidationError>,
        val warnings: MutableList<ImportValidationError>,
    )

    private class SourceTooLargeException : IllegalArgumentException()

    private companion object {
        const val MAX_ERRORS = 100
        const val MAX_ERROR_FIELD_LENGTH = 80
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val FORMULA_PREFIXES = setOf('=', '+', '-', '@')
        val REQUIRED_COLUMNS = setOf(
            "title",
            "primaryAuthor",
            "isbn13",
            "classificationSource",
            "mediaType",
            "location",
            "readingStatus",
            "addedAt",
        )
    }
}
