package dev.ndcshelf.app.domain.model

import java.util.Calendar
import java.util.TimeZone

data class BookEditDraft(
    val title: String,
    val primaryAuthor: String,
    val publisher: String,
    val publishedYear: String,
    val ndcCode: String,
    val ndcEdition: String,
    val location: String,
    val readingStatus: ReadingStatus,
)

data class ValidatedBookEdit(
    val title: String,
    val primaryAuthor: String,
    val publisher: String?,
    val publishedYear: Int?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val location: String,
    val readingStatus: ReadingStatus,
)

data class BookEditValidationError(
    val field: BookEditField,
    val reason: String,
)

enum class BookEditField {
    TITLE,
    PRIMARY_AUTHOR,
    PUBLISHER,
    PUBLISHED_YEAR,
    NDC_CODE,
    NDC_EDITION,
    LOCATION,
}

sealed interface BookEditValidationResult {
    data class Valid(val edit: ValidatedBookEdit) : BookEditValidationResult

    data class Invalid(val errors: List<BookEditValidationError>) : BookEditValidationResult
}

class BookEditValidator(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun validate(draft: BookEditDraft): BookEditValidationResult {
        val errors = mutableListOf<BookEditValidationError>()
        val title = required(draft.title, BookEditField.TITLE, MAX_TEXT_LENGTH, errors)
        val author = required(
            draft.primaryAuthor,
            BookEditField.PRIMARY_AUTHOR,
            MAX_TEXT_LENGTH,
            errors,
        )
        val publisher = optional(draft.publisher, BookEditField.PUBLISHER, MAX_TEXT_LENGTH, errors)
        val ndcCode = optional(draft.ndcCode, BookEditField.NDC_CODE, NDC_MAX_LENGTH, errors)
        val ndcEdition = optional(
            draft.ndcEdition,
            BookEditField.NDC_EDITION,
            NDC_MAX_LENGTH,
            errors,
        )
        val location = required(
            draft.location,
            BookEditField.LOCATION,
            MAX_LOCATION_LENGTH,
            errors,
        )
        val publishedYear = validatePublishedYear(draft.publishedYear, errors)
        if (ndcCode != null && !NDC_CODE_REGEX.matches(ndcCode)) {
            errors += BookEditValidationError(
                BookEditField.NDC_CODE,
                "NDCコードは3桁と任意の小数部で指定してください",
            )
        }
        if (errors.isNotEmpty()) return BookEditValidationResult.Invalid(errors)

        return BookEditValidationResult.Valid(
            ValidatedBookEdit(
                title = requireNotNull(title),
                primaryAuthor = requireNotNull(author),
                publisher = publisher,
                publishedYear = publishedYear,
                ndcCode = ndcCode,
                ndcEdition = ndcEdition,
                location = requireNotNull(location),
                readingStatus = draft.readingStatus,
            ),
        )
    }

    private fun required(
        value: String,
        field: BookEditField,
        maxLength: Int,
        errors: MutableList<BookEditValidationError>,
    ): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            errors += BookEditValidationError(field, "必須項目です")
            return null
        }
        validateText(normalized, field, maxLength, errors)
        return normalized
    }

    private fun optional(
        value: String,
        field: BookEditField,
        maxLength: Int,
        errors: MutableList<BookEditValidationError>,
    ): String? {
        val normalized = value.trim().takeIf(String::isNotEmpty) ?: return null
        validateText(normalized, field, maxLength, errors)
        return normalized
    }

    private fun validateText(
        value: String,
        field: BookEditField,
        maxLength: Int,
        errors: MutableList<BookEditValidationError>,
    ) {
        if (value.length > maxLength) {
            errors += BookEditValidationError(field, "${maxLength}文字以下にしてください")
        }
        if ('\u0000' in value) {
            errors += BookEditValidationError(field, "NUL文字は使用できません")
        }
    }

    private fun validatePublishedYear(
        value: String,
        errors: MutableList<BookEditValidationError>,
    ): Int? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        val year = normalized.toIntOrNull()
        if (year == null) {
            errors += BookEditValidationError(BookEditField.PUBLISHED_YEAR, "整数で指定してください")
            return null
        }
        val maxYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
            timeInMillis = nowMillis()
            get(Calendar.YEAR) + 1
        }
        if (year !in MIN_PUBLISHED_YEAR..maxYear) {
            errors += BookEditValidationError(
                BookEditField.PUBLISHED_YEAR,
                "${MIN_PUBLISHED_YEAR}〜${maxYear}の範囲で指定してください",
            )
            return null
        }
        return year
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 2_000
        const val MAX_LOCATION_LENGTH = 500
        const val NDC_MAX_LENGTH = 32
        const val MIN_PUBLISHED_YEAR = 1
        val NDC_CODE_REGEX = Regex("""\d{3}(?:\.\d+)?""")
    }
}
