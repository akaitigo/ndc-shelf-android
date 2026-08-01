package dev.ndcshelf.app.domain.ai

import dev.ndcshelf.app.domain.model.LibraryBook

sealed interface AiLibrarianRequestResult {
    data class Prepared(
        val draft: AiLibrarianRequestDraft,
    ) : AiLibrarianRequestResult

    data class Rejected(
        val failure: AiLibrarianFailure,
    ) : AiLibrarianRequestResult
}

/**
 * 送信ペイロードの唯一の組み立て口。
 *
 * - 書誌文字列を指示文へ連結せず、必ず[AiLibrarianItem]の構造化fieldへ入れる。
 * - 選択されていない項目はfieldごとnullのまま残し、ペイロードへ現れない。
 * - 制御文字の除去と長さ上限で、書式指定による指示文汚染の余地をさらに狭める。
 */
object AiLibrarianRequestBuilder {
    fun build(
        question: String,
        books: List<LibraryBook>,
        includedFields: Set<AiLibrarianField>,
        tagNamesByWorkId: Map<String, List<String>> = emptyMap(),
        notesByCopyId: Map<String, String> = emptyMap(),
    ): AiLibrarianRequestResult {
        val normalizedQuestion = sanitize(question, AiLibrarianLimits.MAX_QUESTION_LENGTH + 1)
        when {
            normalizedQuestion.isNullOrEmpty() -> {
                return AiLibrarianRequestResult.Rejected(AiLibrarianFailure.QUESTION_EMPTY)
            }

            normalizedQuestion.length > AiLibrarianLimits.MAX_QUESTION_LENGTH -> {
                return AiLibrarianRequestResult.Rejected(AiLibrarianFailure.QUESTION_TOO_LONG)
            }

            books.isEmpty() -> {
                return AiLibrarianRequestResult.Rejected(AiLibrarianFailure.NO_BOOKS_SELECTED)
            }

            books.size > AiLibrarianLimits.MAX_ITEMS_PER_REQUEST -> {
                return AiLibrarianRequestResult.Rejected(AiLibrarianFailure.ITEM_LIMIT_EXCEEDED)
            }
        }

        val effectiveFields = normalizeFields(includedFields)
        val references = mutableListOf<AiLibrarianBookReference>()
        val items =
            books.mapIndexed { index, book ->
                val ref = (index + 1).toString()
                val title = sanitize(book.title, AiLibrarianLimits.MAX_VALUE_LENGTH) ?: AiPayloadLabels.UNTITLED
                references += AiLibrarianBookReference(ref = ref, copyId = book.copyId, title = title)
                AiLibrarianItem(
                    ref = ref,
                    title = title,
                    author = book.valueIf(effectiveFields, AiLibrarianField.AUTHOR) { primaryAuthor },
                    publisher = book.valueIf(effectiveFields, AiLibrarianField.PUBLISHER) { publisher },
                    publishedYear =
                        book.publishedYear.takeIf {
                            AiLibrarianField.PUBLISHED_YEAR in effectiveFields
                        },
                    ndcCode = book.valueIf(effectiveFields, AiLibrarianField.NDC) { ndcCode },
                    ndcCategory =
                        book.valueIf(effectiveFields, AiLibrarianField.NDC) { ndcCategory?.label },
                    tags =
                        if (AiLibrarianField.TAGS in effectiveFields) {
                            tagNamesByWorkId[book.workId]
                                .orEmpty()
                                .mapNotNull { name -> sanitize(name, AiLibrarianLimits.MAX_VALUE_LENGTH) }
                                .take(AiLibrarianLimits.MAX_TAGS_PER_ITEM)
                        } else {
                            emptyList()
                        },
                    location = book.valueIf(effectiveFields, AiLibrarianField.LOCATION) { location },
                    readingStatus =
                        book.valueIf(effectiveFields, AiLibrarianField.READING_STATUS) {
                            readingStatus.aiPayloadLabel()
                        },
                    note =
                        if (AiLibrarianField.NOTE in effectiveFields) {
                            sanitize(notesByCopyId[book.copyId], AiLibrarianLimits.MAX_VALUE_LENGTH)
                        } else {
                            null
                        },
                )
            }

        return AiLibrarianRequestResult.Prepared(
            AiLibrarianRequestDraft(
                request =
                    AiLibrarianRequest(
                        question = normalizedQuestion,
                        includedFields = AiLibrarianField.entries.filter { it in effectiveFields },
                        items = items,
                    ),
                references = references.toList(),
            ),
        )
    }

    /** 必須項目を補い、未知の指定を落とした実効項目集合。 */
    private fun normalizeFields(includedFields: Set<AiLibrarianField>): Set<AiLibrarianField> =
        AiLibrarianField.entries
            .filter { field -> field.required || field in includedFields }
            .toSet()

    private inline fun LibraryBook.valueIf(
        fields: Set<AiLibrarianField>,
        field: AiLibrarianField,
        select: LibraryBook.() -> String?,
    ): String? = if (field in fields) sanitize(select(), AiLibrarianLimits.MAX_VALUE_LENGTH) else null

    /**
     * 制御文字を空白へ置換し、連続空白を1つへ畳み、長さを制限する。空になったらnull。
     * 除去ではなく置換にすることで、改行を挟んだ語が意図せず連結されるのを防ぐ。
     */
    private fun sanitize(
        value: String?,
        maxLength: Int,
    ): String? {
        if (value == null) return null
        val withoutControls =
            buildString(value.length) {
                value.forEach { char -> append(if (char.isISOControl()) ' ' else char) }
            }
        val cleaned =
            withoutControls
                .replace(WHITESPACE_RUN, " ")
                .trim()
                .take(maxLength)
        return cleaned.ifEmpty { null }
    }


    private val WHITESPACE_RUN = Regex("\\s+")
}
