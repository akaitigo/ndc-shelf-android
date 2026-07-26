package dev.ndcshelf.app.domain.model

enum class PurchaseStatus {
    WANTED,
    RESERVED,
}

enum class PurchaseTransition {
    WANTED,
    RESERVED,
    PURCHASED,
}

data class BookstoreBook(
    val workId: String,
    val editionId: String,
    val title: String,
    val primaryAuthor: String,
    val isbn13: String,
    val publisher: String?,
    val publishedYear: Int?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: ClassificationSource,
    val purchaseStatus: PurchaseStatus?,
    val ownedCopyCount: Int,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    fun matches(query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isEmpty()) return true
        return listOf(title, primaryAuthor, isbn13, publisher.orEmpty(), ndcCode.orEmpty())
            .any { it.contains(normalized, ignoreCase = true) }
    }
}
