package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.network.NdlEndpointPolicy
import dev.ndcshelf.app.scanner.Isbn
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class LibraryImportLimits(
    val maxSourceBytes: Long = 10L * 1024 * 1024,
    val maxRecords: Int = 10_000,
    val maxIdLength: Int = 128,
    val maxTextLength: Int = 2_000,
    val maxLocationLength: Int = 500,
    val maxUrlLength: Int = 4_096,
)

data class UnvalidatedLibraryBook(
    val copyId: String?,
    val workId: String?,
    val editionId: String?,
    val title: String?,
    val primaryAuthor: String?,
    val isbn13: String?,
    val publisher: String?,
    val publishedYear: Long?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: String?,
    val mediaType: String?,
    val location: String?,
    val readingStatus: String?,
    val addedAt: Long?,
    val copyLabel: String? = null,
)

data class LibraryImportBatch(
    val sourceSizeBytes: Long,
    val records: List<UnvalidatedLibraryBook>,
)

enum class ImportConflictPolicy {
    SKIP_EXISTING,
    UPDATE_EXISTING,
}

data class ImportValidationError(
    val recordNumber: Int?,
    val field: String?,
    val reason: String,
)

sealed interface ImportPreviewResult {
    data class Valid(val preview: LibraryImportPreview) : ImportPreviewResult

    data class Invalid(val errors: List<ImportValidationError>) : ImportPreviewResult
}

class LibraryImportPreview internal constructor(
    val additions: List<LibraryBook>,
    val updates: List<LibraryBook>,
    val skippedCount: Int,
    val conflictPolicy: ImportConflictPolicy,
    internal val existingSnapshot: List<LibraryBook>,
) {
    val changeCount: Int = additions.size + updates.size
}

sealed interface ImportApplyResult {
    data class Applied(
        val addedCount: Int,
        val updatedCount: Int,
        val skippedCount: Int,
    ) : ImportApplyResult

    data object StalePreview : ImportApplyResult

    data class Failure(val message: String) : ImportApplyResult
}

class LibraryImportPlanner(
    private val limits: LibraryImportLimits = LibraryImportLimits(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun preview(
        batch: LibraryImportBatch,
        existingBooks: List<LibraryBook>,
        conflictPolicy: ImportConflictPolicy,
    ): ImportPreviewResult {
        val errors = mutableListOf<ImportValidationError>()
        if (batch.sourceSizeBytes < 0) {
            errors.addCapped(globalError("入力サイズが不正です"))
        } else if (batch.sourceSizeBytes > limits.maxSourceBytes) {
            errors.addCapped(globalError("入力ファイルは${limits.maxSourceBytes}バイト以下にしてください"))
        }
        if (batch.records.size > limits.maxRecords) {
            errors.addCapped(globalError("蔵書件数は${limits.maxRecords}件以下にしてください"))
        }
        if (errors.isNotEmpty()) return ImportPreviewResult.Invalid(errors)

        val normalized = batch.records.mapIndexedNotNull { index, record ->
            if (errors.size >= MAX_ERRORS) null else normalizeRecord(index + 1, record, errors)
        }
        validateInputReferences(normalized, errors)
        if (errors.isNotEmpty()) return ImportPreviewResult.Invalid(errors.take(MAX_ERRORS))

        val additions = mutableListOf<LibraryBook>()
        val updates = mutableListOf<LibraryBook>()
        var skippedCount = 0
        val existingByCopyId = existingBooks.associateBy(LibraryBook::copyId)
        val existingByIsbn = existingBooks.groupBy(LibraryBook::isbn13)
        val existingByWorkId = existingBooks.groupBy(LibraryBook::workId)
        val existingByEditionId = existingBooks.groupBy(LibraryBook::editionId)
        val inputEditionByIsbn = normalized.groupBy(LibraryBook::isbn13)
            .mapValues { (_, copies) -> copies.first() }

        normalized.groupBy(LibraryBook::isbn13).forEach { (_, copies) ->
            if (copies.map { it.sharedEditionFingerprint() }.distinct().size > 1) {
                val book = copies.last()
                errors.addCapped(recordError(
                    normalized.indexOf(book) + 1,
                    "isbn13",
                    "同じISBNに異なる版情報が指定されています",
                ))
            }
        }
        if (errors.isNotEmpty()) return ImportPreviewResult.Invalid(errors.take(MAX_ERRORS))

        normalized.forEachIndexed { index, rawBook ->
            val byCopyId = existingByCopyId[rawBook.copyId]
            if (byCopyId != null && byCopyId.isbn13 != rawBook.isbn13) {
                errors.addCapped(recordError(
                    index + 1,
                    "isbn13",
                    "copyIdとISBNが異なる既存蔵書を参照しています",
                ))
                return@forEachIndexed
            }

            val existingEdition = existingByIsbn[rawBook.isbn13]?.firstOrNull()
            val sharedEdition = existingEdition ?: inputEditionByIsbn.getValue(rawBook.isbn13)
            if (byCopyId == null && existingEdition != null &&
                existingEdition.sharedEditionFingerprint() != rawBook.sharedEditionFingerprint()
            ) {
                errors.addCapped(recordError(
                    index + 1,
                    "isbn13",
                    "既存ISBNの版情報と一致しません",
                ))
                return@forEachIndexed
            }
            val book = rawBook.copy(
                workId = sharedEdition.workId,
                editionId = sharedEdition.editionId,
            )

            if (byCopyId == null) {
                validateExistingReferences(
                    recordNumber = index + 1,
                    book = book,
                    existingByWorkId = existingByWorkId,
                    existingByEditionId = existingByEditionId,
                    errors = errors,
                )
                additions += book
            } else if (conflictPolicy == ImportConflictPolicy.SKIP_EXISTING) {
                skippedCount += 1
            } else {
                updates += book.copy(
                    copyId = byCopyId.copyId,
                    workId = byCopyId.workId,
                    editionId = byCopyId.editionId,
                )
            }
        }

        validateInputReferences(additions + updates, errors)
        if (errors.isNotEmpty()) return ImportPreviewResult.Invalid(errors.take(MAX_ERRORS))

        return ImportPreviewResult.Valid(
            LibraryImportPreview(
                additions = additions,
                updates = updates,
                skippedCount = skippedCount,
                conflictPolicy = conflictPolicy,
                existingSnapshot = existingBooks.toList(),
            ),
        )
    }

    private fun normalizeRecord(
        recordNumber: Int,
        record: UnvalidatedLibraryBook,
        errors: MutableList<ImportValidationError>,
    ): LibraryBook? {
        val before = errors.size
        val copyId = requiredText(recordNumber, "copyId", record.copyId, limits.maxIdLength, errors)
        val workId = requiredText(recordNumber, "workId", record.workId, limits.maxIdLength, errors)
        val editionId = requiredText(
            recordNumber,
            "editionId",
            record.editionId,
            limits.maxIdLength,
            errors,
        )
        validateId(recordNumber, "copyId", copyId, errors)
        validateId(recordNumber, "workId", workId, errors)
        validateId(recordNumber, "editionId", editionId, errors)
        val title = requiredText(recordNumber, "title", record.title, limits.maxTextLength, errors)
        val author = requiredText(
            recordNumber,
            "primaryAuthor",
            record.primaryAuthor,
            limits.maxTextLength,
            errors,
        )
        val rawIsbn = requiredText(
            recordNumber,
            "isbn13",
            record.isbn13,
            ISBN_MAX_LENGTH,
            errors,
        )
        val isbn13 = rawIsbn?.let(Isbn::normalizeToIsbn13)
        if (rawIsbn != null && isbn13 == null) {
            errors.addCapped(recordError(recordNumber, "isbn13", "ISBNの形式またはチェックデジットが不正です"))
        }
        val publisher = optionalText(
            recordNumber,
            "publisher",
            record.publisher,
            limits.maxTextLength,
            errors,
        )
        val coverUrl = optionalText(
            recordNumber,
            "coverUrl",
            record.coverUrl,
            limits.maxUrlLength,
            errors,
        )
        if (coverUrl != null && !NdlEndpointPolicy.isAllowedCoverUrl(coverUrl, isbn13)) {
            errors.addCapped(recordError(recordNumber, "coverUrl", "NDL Searchのhttps URLを指定してください"))
        }
        val ndcCode = optionalText(
            recordNumber,
            "ndcCode",
            record.ndcCode,
            NDC_CODE_MAX_LENGTH,
            errors,
        )
        if (ndcCode != null && !NDC_CODE_REGEX.matches(ndcCode)) {
            errors.addCapped(recordError(recordNumber, "ndcCode", "NDCコードは3桁と任意の小数部で指定してください"))
        }
        val ndcEdition = optionalText(
            recordNumber,
            "ndcEdition",
            record.ndcEdition,
            NDC_EDITION_MAX_LENGTH,
            errors,
        )
        val location = requiredText(
            recordNumber,
            "location",
            record.location,
            limits.maxLocationLength,
            errors,
        )
        val classificationSource = enumValue<ClassificationSource>(
            recordNumber,
            "classificationSource",
            record.classificationSource,
            errors,
        )
        val mediaType = enumValue<MediaType>(
            recordNumber,
            "mediaType",
            record.mediaType,
            errors,
        )
        val readingStatus = enumValue<ReadingStatus>(
            recordNumber,
            "readingStatus",
            record.readingStatus,
            errors,
        )
        val publishedYear = normalizePublishedYear(recordNumber, record.publishedYear, errors)
        val addedAt = normalizeAddedAt(recordNumber, record.addedAt, errors)
        val copyLabel = requiredText(
            recordNumber,
            "copyLabel",
            record.copyLabel ?: DEFAULT_COPY_LABEL,
            MAX_COPY_LABEL_LENGTH,
            errors,
        )

        if (errors.size != before) return null
        return LibraryBook(
            copyId = requireNotNull(copyId),
            workId = requireNotNull(workId),
            editionId = requireNotNull(editionId),
            title = requireNotNull(title),
            primaryAuthor = requireNotNull(author),
            isbn13 = requireNotNull(isbn13),
            publisher = publisher,
            publishedYear = publishedYear,
            coverUrl = coverUrl,
            ndcCode = ndcCode,
            ndcEdition = ndcEdition,
            classificationSource = requireNotNull(classificationSource),
            mediaType = requireNotNull(mediaType),
            location = requireNotNull(location),
            readingStatus = requireNotNull(readingStatus),
            addedAt = requireNotNull(addedAt),
            copyLabel = requireNotNull(copyLabel),
        )
    }

    private fun validateInputReferences(
        books: List<LibraryBook>,
        errors: MutableList<ImportValidationError>,
    ) {
        reportDuplicates(books, "copyId", LibraryBook::copyId, errors)
        books.groupBy(LibraryBook::workId).forEach { (_, group) ->
            if (group.map { it.title to it.primaryAuthor }.distinct().size > 1) {
                val book = group.last()
                errors.addCapped(recordError(
                    books.indexOf(book) + 1,
                    "workId",
                    "同じworkIdに異なる書誌情報が指定されています",
                ))
            }
        }
        books.groupBy(LibraryBook::editionId).forEach { (_, group) ->
            if (group.map { it.editionFingerprint() }.distinct().size > 1) {
                val book = group.last()
                errors.addCapped(recordError(
                    books.indexOf(book) + 1,
                    "editionId",
                    "同じeditionIdに異なる版情報が指定されています",
                ))
            }
        }
    }

    private fun validateExistingReferences(
        recordNumber: Int,
        book: LibraryBook,
        existingByWorkId: Map<String, List<LibraryBook>>,
        existingByEditionId: Map<String, List<LibraryBook>>,
        errors: MutableList<ImportValidationError>,
    ) {
        existingByWorkId[book.workId]?.firstOrNull()?.let { existing ->
            if (existing.title != book.title || existing.primaryAuthor != book.primaryAuthor) {
                errors.addCapped(recordError(
                    recordNumber,
                    "workId",
                    "既存workIdの書誌情報と一致しません",
                ))
            }
        }
        existingByEditionId[book.editionId]?.firstOrNull()?.let { existing ->
            if (existing.editionFingerprint() != book.editionFingerprint()) {
                errors.addCapped(recordError(
                    recordNumber,
                    "editionId",
                    "既存editionIdの版情報と一致しません",
                ))
            }
        }
    }

    private fun requiredText(
        recordNumber: Int,
        field: String,
        value: String?,
        maxLength: Int,
        errors: MutableList<ImportValidationError>,
    ): String? {
        val normalized = value?.trim()
        if (normalized.isNullOrEmpty()) {
            errors.addCapped(recordError(recordNumber, field, "必須項目です"))
            return null
        }
        validateLengthAndControls(recordNumber, field, normalized, maxLength, errors)
        return normalized
    }

    private fun optionalText(
        recordNumber: Int,
        field: String,
        value: String?,
        maxLength: Int,
        errors: MutableList<ImportValidationError>,
    ): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        validateLengthAndControls(recordNumber, field, normalized, maxLength, errors)
        return normalized
    }

    private fun validateLengthAndControls(
        recordNumber: Int,
        field: String,
        value: String,
        maxLength: Int,
        errors: MutableList<ImportValidationError>,
    ) {
        if (value.length > maxLength) {
            errors.addCapped(recordError(recordNumber, field, "${maxLength}文字以下にしてください"))
        }
        if (value.any { it == '\u0000' }) {
            errors.addCapped(recordError(recordNumber, field, "NUL文字は使用できません"))
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(
        recordNumber: Int,
        field: String,
        value: String?,
        errors: MutableList<ImportValidationError>,
    ): T? {
        val normalized = value?.trim()?.uppercase(Locale.ROOT)
        val result = enumValues<T>().firstOrNull { it.name == normalized }
        if (result == null) {
            errors.addCapped(recordError(recordNumber, field, "未知の値です"))
        }
        return result
    }

    private fun normalizePublishedYear(
        recordNumber: Int,
        value: Long?,
        errors: MutableList<ImportValidationError>,
    ): Int? {
        if (value == null) return null
        val maxYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
            timeInMillis = nowMillis()
            get(Calendar.YEAR) + 1
        }
        if (value !in MIN_PUBLISHED_YEAR.toLong()..maxYear.toLong()) {
            errors.addCapped(recordError(
                recordNumber,
                "publishedYear",
                "${MIN_PUBLISHED_YEAR}〜${maxYear}の範囲で指定してください",
            ))
            return null
        }
        return value.toInt()
    }

    private fun normalizeAddedAt(
        recordNumber: Int,
        value: Long?,
        errors: MutableList<ImportValidationError>,
    ): Long? {
        val maxTimestamp = nowMillis() + MAX_CLOCK_SKEW_MILLIS
        if (value == null || value !in 0..maxTimestamp) {
            errors.addCapped(recordError(recordNumber, "addedAt", "有効なUnix epoch millisecondsではありません"))
            return null
        }
        return value
    }

    private fun reportDuplicates(
        books: List<LibraryBook>,
        field: String,
        selector: (LibraryBook) -> String,
        errors: MutableList<ImportValidationError>,
    ) {
        val firstIndexes = mutableMapOf<String, Int>()
        books.forEachIndexed { index, book ->
            val key = selector(book)
            val first = firstIndexes[key]
            if (first == null) {
                firstIndexes[key] = index
            } else {
                errors.addCapped(recordError(index + 1, field, "レコード${first + 1}と重複しています"))
            }
        }
    }

    private fun validateId(
        recordNumber: Int,
        field: String,
        value: String?,
        errors: MutableList<ImportValidationError>,
    ) {
        if (value != null && !ID_REGEX.matches(value)) {
            errors.addCapped(recordError(recordNumber, field, "英数字、ピリオド、コロン、アンダースコア、ハイフンのみ使用できます"))
        }
    }

    private fun MutableList<ImportValidationError>.addCapped(error: ImportValidationError) {
        if (size < MAX_ERRORS) add(error)
    }

    private fun globalError(reason: String) = ImportValidationError(null, null, reason)

    private fun recordError(record: Int, field: String, reason: String) =
        ImportValidationError(record, field, reason)

    private fun LibraryBook.editionFingerprint(): List<Any?> = listOf(
        workId,
        isbn13,
        publisher,
        publishedYear,
        coverUrl,
        ndcCode,
        ndcEdition,
        classificationSource,
    )

    private fun LibraryBook.sharedEditionFingerprint(): List<Any?> = listOf(
        title,
        primaryAuthor,
        isbn13,
        publisher,
        publishedYear,
        coverUrl,
        ndcCode,
        ndcEdition,
        classificationSource,
    )

    private companion object {
        const val MAX_ERRORS = 100
        const val ISBN_MAX_LENGTH = 32
        const val NDC_CODE_MAX_LENGTH = 32
        const val NDC_EDITION_MAX_LENGTH = 32
        const val MAX_COPY_LABEL_LENGTH = 100
        const val DEFAULT_COPY_LABEL = "所蔵本"
        const val MIN_PUBLISHED_YEAR = 1
        const val MAX_CLOCK_SKEW_MILLIS = 24 * 60 * 60 * 1000L
        val NDC_CODE_REGEX = Regex("""\d{3}(?:\.\d+)?""")
        val ID_REGEX = Regex("""[A-Za-z0-9._:-]+""")
    }
}
