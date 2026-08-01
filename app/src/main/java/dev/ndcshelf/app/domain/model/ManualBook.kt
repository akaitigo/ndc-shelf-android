package dev.ndcshelf.app.domain.model

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.text.UiMessage
import dev.ndcshelf.app.scanner.Isbn
import java.util.Calendar
import java.util.TimeZone

data class ManualBookDraft(
    val title: String,
    val primaryAuthor: String = "",
    val isbn: String = "",
    val publisher: String = "",
    val publishedYear: String = "",
    val ndcCode: String = "",
    val ndcEdition: String = "",
    val mediaType: MediaType = MediaType.PHYSICAL,
)

data class ValidatedManualBook(
    val title: String,
    val primaryAuthor: String,
    val isbn13: String?,
    val publisher: String?,
    val publishedYear: Int?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val mediaType: MediaType,
)

data class ManualBookValidationError(
    val field: ManualBookField,
    val reason: UiMessage,
)

enum class ManualBookField {
    TITLE,
    PRIMARY_AUTHOR,
    ISBN,
    PUBLISHER,
    PUBLISHED_YEAR,
    NDC_CODE,
    NDC_EDITION,
}

sealed interface ManualBookValidationResult {
    data class Valid(val book: ValidatedManualBook) : ManualBookValidationResult
    data class Invalid(val errors: List<ManualBookValidationError>) : ManualBookValidationResult
}

class ManualBookValidator(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun validate(draft: ManualBookDraft): ManualBookValidationResult {
        val errors = mutableListOf<ManualBookValidationError>()
        val title = required(draft.title, ManualBookField.TITLE, MAX_TEXT_LENGTH, errors)
        val author = optional(
            draft.primaryAuthor,
            ManualBookField.PRIMARY_AUTHOR,
            MAX_TEXT_LENGTH,
            errors,
        )
        val publisher = optional(
            draft.publisher,
            ManualBookField.PUBLISHER,
            MAX_TEXT_LENGTH,
            errors,
        )
        val ndcCode = optional(draft.ndcCode, ManualBookField.NDC_CODE, NDC_MAX_LENGTH, errors)
        val ndcEdition = optional(
            draft.ndcEdition,
            ManualBookField.NDC_EDITION,
            NDC_MAX_LENGTH,
            errors,
        )
        val rawIsbn = optional(draft.isbn, ManualBookField.ISBN, ISBN_MAX_LENGTH, errors)
        val isbn13 = rawIsbn?.let(Isbn::normalizeToIsbn13)
        if (rawIsbn != null && isbn13 == null) {
            errors += ManualBookValidationError(
                ManualBookField.ISBN,
                UiMessage(R.string.validation_invalid_isbn),
            )
        }
        if (ndcCode != null && !NDC_CODE_REGEX.matches(ndcCode)) {
            errors += ManualBookValidationError(
                ManualBookField.NDC_CODE,
                UiMessage(R.string.validation_invalid_ndc),
            )
        }
        val year = validatePublishedYear(draft.publishedYear, errors)
        if (errors.isNotEmpty()) return ManualBookValidationResult.Invalid(errors)

        return ManualBookValidationResult.Valid(
            ValidatedManualBook(
                title = requireNotNull(title),
                primaryAuthor = author ?: UNKNOWN_AUTHOR,
                isbn13 = isbn13,
                publisher = publisher,
                publishedYear = year,
                ndcCode = ndcCode,
                ndcEdition = ndcEdition,
                mediaType = draft.mediaType,
            ),
        )
    }

    private fun required(
        value: String,
        field: ManualBookField,
        maxLength: Int,
        errors: MutableList<ManualBookValidationError>,
    ): String? {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            errors += ManualBookValidationError(field, UiMessage(R.string.validation_required))
            return null
        }
        validateText(normalized, field, maxLength, errors)
        return normalized
    }

    private fun optional(
        value: String,
        field: ManualBookField,
        maxLength: Int,
        errors: MutableList<ManualBookValidationError>,
    ): String? = value.trim().takeIf(String::isNotEmpty)?.also {
        validateText(it, field, maxLength, errors)
    }

    private fun validateText(
        value: String,
        field: ManualBookField,
        maxLength: Int,
        errors: MutableList<ManualBookValidationError>,
    ) {
        if (value.length > maxLength) {
            errors += ManualBookValidationError(
                field,
                UiMessage(R.string.validation_max_length, maxLength),
            )
        }
        if ('\u0000' in value) errors += ManualBookValidationError(field, UiMessage(R.string.validation_no_nul))
    }

    private fun validatePublishedYear(
        value: String,
        errors: MutableList<ManualBookValidationError>,
    ): Int? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        val year = normalized.toIntOrNull()
        val maxYear = Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
            timeInMillis = nowMillis()
            get(Calendar.YEAR) + 1
        }
        if (year == null || year !in MIN_PUBLISHED_YEAR..maxYear) {
            errors += ManualBookValidationError(
                ManualBookField.PUBLISHED_YEAR,
                UiMessage(R.string.validation_year_range, MIN_PUBLISHED_YEAR, maxYear),
            )
            return null
        }
        return year
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 2_000
        const val NDC_MAX_LENGTH = 32
        const val ISBN_MAX_LENGTH = 32
        const val MIN_PUBLISHED_YEAR = 1
        const val UNKNOWN_AUTHOR = "著者不明"
        val NDC_CODE_REGEX = Regex("""\d{3}(?:\.\d+)?""")
    }
}

data class NdlReconciliationCandidate(
    val isbn13: String,
    val title: String,
    val primaryAuthor: String,
    val publisher: String?,
    val publishedYear: Int?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: ClassificationSource,
)

data class ManualReconciliationPreview(
    val current: LibraryBook,
    val candidate: NdlReconciliationCandidate,
    val existingEditionId: String?,
    val existingCopyCount: Int,
    val currentEditionCopyCount: Int,
    val existingEditionSnapshot: ReconciliationEditionSnapshot?,
)

data class ReconciliationEditionSnapshot(
    val workId: String,
    val editionId: String,
    val title: String,
    val primaryAuthor: String,
    val isbn13: String?,
    val publisher: String?,
    val publishedYear: Int?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: ClassificationSource,
    val bibliographicSource: BibliographicSource,
)
