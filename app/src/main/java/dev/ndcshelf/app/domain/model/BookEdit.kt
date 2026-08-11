package dev.ndcshelf.app.domain.model

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.text.UiMessage
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
    val locationTierId: String? = null,
    val locationInsertAfterCopyId: String? = null,
    val locationInsertAtStart: Boolean = false,
    val locationPositionSpecified: Boolean = false,
    val copyLabel: String = LibraryDefaults.UNSET_COPY_LABEL,
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
    val locationTierId: String? = null,
    val locationInsertAfterCopyId: String? = null,
    val locationInsertAtStart: Boolean = false,
    val locationPositionSpecified: Boolean = false,
    val copyLabel: String,
)

data class BookEditValidationError(
    val field: BookEditField,
    val reason: UiMessage,
)

enum class BookEditField {
    TITLE,
    PRIMARY_AUTHOR,
    PUBLISHER,
    PUBLISHED_YEAR,
    NDC_CODE,
    NDC_EDITION,
    LOCATION,
    COPY_LABEL,
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
        // 置き場所と所蔵ラベルは未設定を許す。保存値は端末ロケールに依存しない空文字とし、
        // 表示側が location_unset_value / copy_label_default でlocalizeする。
        val location = optional(
            LibraryDefaults.normalizeLocation(draft.location),
            BookEditField.LOCATION,
            MAX_LOCATION_LENGTH,
            errors,
        ) ?: LibraryDefaults.UNSET_LOCATION
        val copyLabel = optional(
            LibraryDefaults.normalizeCopyLabel(draft.copyLabel),
            BookEditField.COPY_LABEL,
            MAX_COPY_LABEL_LENGTH,
            errors,
        ) ?: LibraryDefaults.UNSET_COPY_LABEL
        val publishedYear = validatePublishedYear(draft.publishedYear, errors)
        if (ndcCode != null && !NDC_CODE_REGEX.matches(ndcCode)) {
            errors += BookEditValidationError(
                BookEditField.NDC_CODE,
                UiMessage(R.string.validation_invalid_ndc),
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
                location = location,
                readingStatus = draft.readingStatus,
                locationTierId = draft.locationTierId,
                locationInsertAfterCopyId = draft.locationInsertAfterCopyId,
                locationInsertAtStart = draft.locationInsertAtStart,
                locationPositionSpecified = draft.locationPositionSpecified,
                copyLabel = copyLabel,
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
            errors += BookEditValidationError(field, UiMessage(R.string.validation_required))
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
            errors += BookEditValidationError(
                field,
                UiMessage(R.string.validation_max_length, maxLength),
            )
        }
        if ('\u0000' in value) {
            errors += BookEditValidationError(field, UiMessage(R.string.validation_no_nul))
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
            errors += BookEditValidationError(
                BookEditField.PUBLISHED_YEAR,
                UiMessage(R.string.validation_year_integer),
            )
            return null
        }
        val maxYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
            timeInMillis = nowMillis()
            get(Calendar.YEAR) + 1
        }
        if (year !in MIN_PUBLISHED_YEAR..maxYear) {
            errors += BookEditValidationError(
                BookEditField.PUBLISHED_YEAR,
                UiMessage(R.string.validation_year_range, MIN_PUBLISHED_YEAR, maxYear),
            )
            return null
        }
        return year
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 2_000
        const val MAX_LOCATION_LENGTH = 500
        const val MAX_COPY_LABEL_LENGTH = 100
        const val NDC_MAX_LENGTH = 32
        const val MIN_PUBLISHED_YEAR = 1
        val NDC_CODE_REGEX = Regex("""\d{3}(?:\.\d+)?""")
    }
}
