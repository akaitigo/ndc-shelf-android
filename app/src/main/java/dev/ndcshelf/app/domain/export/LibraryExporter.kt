package dev.ndcshelf.app.domain.export

import dev.ndcshelf.app.domain.model.LibraryBook
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.Writer
import java.nio.charset.StandardCharsets

enum class LibraryExportFormat(
    val extension: String,
    val mimeType: String,
) {
    JSON(extension = "json", mimeType = "application/json"),
    CSV(extension = "csv", mimeType = "text/csv"),
}

object LibraryExporter {
    private const val SCHEMA_VERSION = 1
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun write(
        books: List<LibraryBook>,
        format: LibraryExportFormat,
        output: OutputStream,
        exportedAt: Long = System.currentTimeMillis(),
    ) {
        if (format == LibraryExportFormat.CSV) output.write(utf8Bom)
        val writer = output.writer(StandardCharsets.UTF_8).buffered()
        when (format) {
            LibraryExportFormat.JSON -> writer.writeJson(books, exportedAt)
            LibraryExportFormat.CSV -> writer.writeCsv(books)
        }
        writer.flush()
    }

    internal fun export(
        books: List<LibraryBook>,
        format: LibraryExportFormat,
        exportedAt: Long = System.currentTimeMillis(),
    ): ByteArray = ByteArrayOutputStream().use { output ->
        write(books, format, output, exportedAt)
        output.toByteArray()
    }

    private fun Writer.writeJson(books: List<LibraryBook>, exportedAt: Long) {
        append("{\n")
        append("  \"schemaVersion\": ").append(SCHEMA_VERSION.toString()).append(",\n")
        append("  \"exportedAt\": ").append(exportedAt.toString()).append(",\n")
        append("  \"bookCount\": ").append(books.size.toString()).append(",\n")
        append("  \"books\": ")
        if (books.isEmpty()) {
            append("[]\n")
        } else {
            append("[\n")
        }
        books.forEachIndexed { index, book ->
            append("    {\n")
            appendJsonField("copyId", book.copyId)
            appendJsonField("workId", book.workId)
            appendJsonField("editionId", book.editionId)
            appendJsonField("title", book.title)
            appendJsonField("primaryAuthor", book.primaryAuthor)
            appendJsonField("isbn13", book.isbn13)
            appendJsonField("publisher", book.publisher)
            appendJsonNumber("publishedYear", book.publishedYear)
            appendJsonField("coverUrl", book.coverUrl)
            appendJsonField("ndcCode", book.ndcCode)
            appendJsonField("ndcEdition", book.ndcEdition)
            appendJsonField("classificationSource", book.classificationSource.name)
            appendJsonField("mediaType", book.mediaType.name)
            appendJsonField("location", book.location)
            appendJsonField("readingStatus", book.readingStatus.name)
            append("      \"addedAt\": ").append(book.addedAt.toString()).append('\n')
            append("    }")
            if (index != books.lastIndex) append(',')
            append('\n')
        }
        if (books.isNotEmpty()) append("  ]\n")
        append('}')
    }

    private fun Writer.appendJsonField(name: String, value: String?) {
        append("      \"").append(name).append("\": ")
        if (value == null) {
            append("null")
        } else {
            append('"')
            appendEscapedJson(value)
            append('"')
        }
        append(",\n")
    }

    private fun Writer.appendJsonNumber(name: String, value: Int?) {
        append("      \"").append(name).append("\": ")
        if (value == null) append("null") else append(value.toString())
        append(",\n")
    }

    private fun Writer.appendEscapedJson(value: String) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun Writer.writeCsv(books: List<LibraryBook>) {
        append(CSV_COLUMNS.joinToString(",") { it.csvCell() }).append("\r\n")
        books.forEach { book ->
            append(
                listOf(
                    book.copyId,
                    book.workId,
                    book.editionId,
                    book.title,
                    book.primaryAuthor,
                    book.isbn13,
                    book.publisher.orEmpty(),
                    book.publishedYear?.toString().orEmpty(),
                    book.coverUrl.orEmpty(),
                    book.ndcCode.orEmpty(),
                    book.ndcEdition.orEmpty(),
                    book.classificationSource.name,
                    book.mediaType.name,
                    book.location,
                    book.readingStatus.name,
                    book.addedAt.toString(),
                ).joinToString(",") { it.safeForSpreadsheet().csvCell() },
            )
            append("\r\n")
        }
    }

    private fun String.csvCell(): String = "\"${replace("\"", "\"\"")}\""

    private fun String.safeForSpreadsheet(): String {
        val firstMeaningful = firstOrNull { !it.isWhitespace() }
        return if (firstMeaningful in FORMULA_PREFIXES) "'$this" else this
    }

    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@')

    private val CSV_COLUMNS = listOf(
        "copyId",
        "workId",
        "editionId",
        "title",
        "primaryAuthor",
        "isbn13",
        "publisher",
        "publishedYear",
        "coverUrl",
        "ndcCode",
        "ndcEdition",
        "classificationSource",
        "mediaType",
        "location",
        "readingStatus",
        "addedAt",
    )
}
