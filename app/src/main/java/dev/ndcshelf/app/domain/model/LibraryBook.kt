package dev.ndcshelf.app.domain.model

data class LibraryBook(
    val copyId: String,
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
    val mediaType: MediaType,
    val location: String,
    val readingStatus: ReadingStatus,
    val addedAt: Long,
    val locationTierId: String? = null,
    val shelfOrderKey: String? = null,
    val copyLabel: String = "所蔵本",
    val bibliographicSource: BibliographicSource = BibliographicSource.NDL,
) {
    val ndcCategory: NdcCategory?
        get() = NdcCategory.fromCode(ndcCode)

    fun matches(query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isEmpty()) return true

        return listOfNotNull(
            title,
            primaryAuthor,
            isbn13,
            publisher,
            ndcCode,
            ndcCategory?.label,
            location,
            copyLabel,
        ).any { value -> value.contains(normalized, ignoreCase = true) }
    }
}

enum class ReadingStatus(
    val label: String,
) {
    UNREAD("未読"),
    READING("読書中"),
    READ("読了"),
    PAUSED("中断"),
}

enum class MediaType {
    PHYSICAL,
    DIGITAL,
}

enum class ClassificationSource {
    NDL,
    MANUAL,
    UNKNOWN,
}

enum class BibliographicSource {
    NDL,
    MANUAL,
}

data class NdcCategory(
    val digit: Int,
    val label: String,
) {
    companion object {
        /** NDC第1次区分（類）の一覧。digit昇順。 */
        val all =
            listOf(
                NdcCategory(0, "総記"),
                NdcCategory(1, "哲学"),
                NdcCategory(2, "歴史"),
                NdcCategory(3, "社会科学"),
                NdcCategory(4, "自然科学"),
                NdcCategory(5, "技術"),
                NdcCategory(6, "産業"),
                NdcCategory(7, "芸術"),
                NdcCategory(8, "言語"),
                NdcCategory(9, "文学"),
            )

        fun fromCode(code: String?): NdcCategory? {
            val digit = code?.firstOrNull()?.digitToIntOrNull() ?: return null
            return all.getOrNull(digit)
        }
    }
}
