package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.domain.export.LibraryExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface LibraryJsonParseResult {
    data class Valid(
        val batch: LibraryImportBatch,
        val exportedAt: Long,
    ) : LibraryJsonParseResult

    data class Invalid(val errors: List<ImportValidationError>) : LibraryJsonParseResult
}

class LibraryJsonImporter(
    private val limits: LibraryImportLimits = LibraryImportLimits(),
) {
    suspend fun parse(input: InputStream): LibraryJsonParseResult {
        val bytes = try {
            readLimited(input)
        } catch (_: SourceTooLargeException) {
            return invalid("入力ファイルは${limits.maxSourceBytes}バイト以下にしてください")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return invalid("JSONファイルを読み込めませんでした")
        }

        val source = try {
            decodeUtf8(bytes)
        } catch (_: Exception) {
            return invalid("JSONファイルはUTF-8で保存してください")
        }
        currentCoroutineContext().ensureActive()
        if (!hasSafeNestingDepth(source)) {
            return invalid("JSONのネストは${MAX_NESTING_DEPTH}段以下にしてください")
        }

        val root = try {
            Json.parseToJsonElement(source) as? JsonObject
                ?: return invalid("JSONのルートはオブジェクトにしてください")
        } catch (_: Exception) {
            return invalid("JSONの構文が正しくありません")
        }
        currentCoroutineContext().ensureActive()

        val errors = mutableListOf<ImportValidationError>()
        reportUnknownFields(root, ROOT_FIELDS, null, errors)
        val schemaVersion = root.requiredLong("schemaVersion", null, errors)
        val unsupportedSchema = schemaVersion != null &&
            schemaVersion !in 1..LibraryExporter.SCHEMA_VERSION.toLong()
        if (unsupportedSchema) {
            errors.addCapped(
                ImportValidationError(
                    recordNumber = null,
                    field = "schemaVersion",
                    reason = if (schemaVersion > LibraryExporter.SCHEMA_VERSION) {
                        "このアプリでは新しいスキーマバージョン${schemaVersion}を読み込めません"
                    } else {
                        "スキーマバージョン${schemaVersion}はサポートしていません"
                    },
                ),
            )
        }
        val exportedAt = root.requiredLong("exportedAt", null, errors)
        if (exportedAt != null && exportedAt < 0) {
            errors.addCapped(rootError("exportedAt", "0以上のUnix epoch millisecondsを指定してください"))
        }
        val declaredBookCount = root.requiredLong("bookCount", null, errors)
        if (declaredBookCount != null && declaredBookCount !in 0..limits.maxRecords.toLong()) {
            errors.addCapped(rootError("bookCount", "0〜${limits.maxRecords}件の範囲で指定してください"))
        }
        val books = root["books"] as? JsonArray
        val tooManyBooks = books != null && books.size > limits.maxRecords
        if (books == null) {
            errors.addCapped(rootError("books", "配列として指定してください"))
        } else {
            if (tooManyBooks) {
                errors.addCapped(rootError("books", "蔵書件数は${limits.maxRecords}件以下にしてください"))
            }
            if (declaredBookCount != null && declaredBookCount != books.size.toLong()) {
                errors.addCapped(rootError("bookCount", "booksの実際の件数と一致しません"))
            }
        }
        if (unsupportedSchema || schemaVersion == null || books == null || tooManyBooks) {
            return LibraryJsonParseResult.Invalid(errors)
        }

        val records = mutableListOf<UnvalidatedLibraryBook>()
        for ((index, element) in books.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (errors.size < MAX_ERRORS) {
                parseBook(index + 1, element, requireNotNull(schemaVersion).toInt(), errors)
                    ?.let(records::add)
            }
        }
        if (errors.isNotEmpty()) return LibraryJsonParseResult.Invalid(errors)

        return LibraryJsonParseResult.Valid(
            batch = LibraryImportBatch(
                sourceSizeBytes = bytes.size.toLong(),
                records = records,
            ),
            exportedAt = requireNotNull(exportedAt),
        )
    }

    private fun parseBook(
        recordNumber: Int,
        element: JsonElement,
        schemaVersion: Int,
        errors: MutableList<ImportValidationError>,
    ): UnvalidatedLibraryBook? {
        val book = element as? JsonObject
        if (book == null) {
            errors.addCapped(recordError(recordNumber, null, "蔵書レコードはオブジェクトにしてください"))
            return null
        }
        val before = errors.size
        reportUnknownFields(book, BOOK_FIELDS, recordNumber, errors)
        val requiredFields = if (schemaVersion >= 2) BOOK_FIELDS else BOOK_FIELDS - "copyLabel"
        requiredFields.forEach { field ->
            if (field !in book) {
                errors.addCapped(recordError(recordNumber, field, "項目がありません"))
            }
        }

        val copyId = book.string("copyId", recordNumber, nullable = false, errors)
        val workId = book.string("workId", recordNumber, nullable = false, errors)
        val editionId = book.string("editionId", recordNumber, nullable = false, errors)
        val title = book.string("title", recordNumber, nullable = false, errors)
        val primaryAuthor = book.string("primaryAuthor", recordNumber, nullable = false, errors)
        val isbn13 = book.string("isbn13", recordNumber, nullable = false, errors)
        val publisher = book.string("publisher", recordNumber, nullable = true, errors)
        val publishedYear = book.long("publishedYear", recordNumber, nullable = true, errors)
        val coverUrl = book.string("coverUrl", recordNumber, nullable = true, errors)
        val ndcCode = book.string("ndcCode", recordNumber, nullable = true, errors)
        val ndcEdition = book.string("ndcEdition", recordNumber, nullable = true, errors)
        val classificationSource = book.string(
            "classificationSource",
            recordNumber,
            nullable = false,
            errors,
        )
        val mediaType = book.string("mediaType", recordNumber, nullable = false, errors)
        val location = book.string("location", recordNumber, nullable = false, errors)
        val readingStatus = book.string("readingStatus", recordNumber, nullable = false, errors)
        val copyLabel = book.string("copyLabel", recordNumber, nullable = false, errors)
        val addedAt = book.long("addedAt", recordNumber, nullable = false, errors)

        if (errors.size != before) return null
        return UnvalidatedLibraryBook(
            copyId = copyId,
            workId = workId,
            editionId = editionId,
            title = title,
            primaryAuthor = primaryAuthor,
            isbn13 = isbn13,
            publisher = publisher,
            publishedYear = publishedYear,
            coverUrl = coverUrl,
            ndcCode = ndcCode,
            ndcEdition = ndcEdition,
            classificationSource = classificationSource,
            mediaType = mediaType,
            location = location,
            readingStatus = readingStatus,
            addedAt = addedAt,
            copyLabel = copyLabel,
        )
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

    private fun hasSafeNestingDepth(source: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        source.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > MAX_NESTING_DEPTH) return false
                    }
                    '}', ']' -> depth -= 1
                }
            }
        }
        return true
    }

    private fun reportUnknownFields(
        value: JsonObject,
        knownFields: Set<String>,
        recordNumber: Int?,
        errors: MutableList<ImportValidationError>,
    ) {
        (value.keys - knownFields).forEach { field ->
            errors.addCapped(
                ImportValidationError(recordNumber, field.safeForError(), "未知の項目です"),
            )
        }
    }

    private fun String.safeForError(): String = if (length <= MAX_ERROR_FIELD_LENGTH) {
        this
    } else {
        take(MAX_ERROR_FIELD_LENGTH) + "…"
    }

    private fun JsonObject.requiredLong(
        field: String,
        recordNumber: Int?,
        errors: MutableList<ImportValidationError>,
    ): Long? = primitiveLong(field, recordNumber, nullable = false, errors)

    private fun JsonObject.long(
        field: String,
        recordNumber: Int,
        nullable: Boolean,
        errors: MutableList<ImportValidationError>,
    ): Long? = primitiveLong(field, recordNumber, nullable, errors)

    private fun JsonObject.primitiveLong(
        field: String,
        recordNumber: Int?,
        nullable: Boolean,
        errors: MutableList<ImportValidationError>,
    ): Long? {
        val value = this[field]
        if (value == null) {
            errors.addCapped(ImportValidationError(recordNumber, field, "項目がありません"))
            return null
        }
        if (value is JsonNull && nullable) return null
        val primitive = value as? JsonPrimitive
        val result = primitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
        if (result == null) {
            errors.addCapped(ImportValidationError(recordNumber, field, "整数として指定してください"))
        }
        return result
    }

    private fun JsonObject.string(
        field: String,
        recordNumber: Int,
        nullable: Boolean,
        errors: MutableList<ImportValidationError>,
    ): String? {
        val value = this[field] ?: return null
        if (value is JsonNull && nullable) return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            errors.addCapped(recordError(recordNumber, field, "文字列として指定してください"))
            return null
        }
        return primitive.content
    }

    private fun ByteArray.startsWithUtf8Bom(): Boolean =
        size >= UTF8_BOM.size && UTF8_BOM.indices.all { this[it] == UTF8_BOM[it] }

    private fun MutableList<ImportValidationError>.addCapped(error: ImportValidationError) {
        if (size < MAX_ERRORS) add(error)
    }

    private fun invalid(reason: String) = LibraryJsonParseResult.Invalid(
        listOf(ImportValidationError(null, null, reason)),
    )

    private fun rootError(field: String, reason: String) =
        ImportValidationError(null, field, reason)

    private fun recordError(record: Int, field: String?, reason: String) =
        ImportValidationError(record, field, reason)

    private class SourceTooLargeException : IllegalArgumentException()

    private companion object {
        const val MAX_ERRORS = 100
        const val MAX_ERROR_FIELD_LENGTH = 80
        const val MAX_NESTING_DEPTH = 64
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val ROOT_FIELDS = setOf("schemaVersion", "exportedAt", "bookCount", "books")
        val BOOK_FIELDS = setOf(
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
            "copyLabel",
            "addedAt",
        )
    }
}
