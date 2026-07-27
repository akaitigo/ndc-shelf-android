package dev.ndcshelf.app.domain.model

data class WorkGroup(
    val id: String,
    val title: String,
    val primaryAuthor: String,
    val seriesSubstitutionEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class WorkGroupMembership(
    val id: String,
    val groupId: String,
    val workId: String,
    val createdAt: Long,
)

data class EditionVariant(
    val id: String,
    val isbn13: String?,
    val publisher: String?,
    val publishedYear: Int?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: ClassificationSource,
    val bibliographicSource: BibliographicSource,
    val mediaTypes: Set<MediaType>,
    val ownedCopyCount: Int,
    val wishlistStatus: PurchaseStatus?,
)

data class WorkVariant(
    val workId: String,
    val title: String,
    val primaryAuthor: String,
    val editions: List<EditionVariant>,
    val membership: WorkGroupMembership? = null,
)

enum class WorkVariantSuggestionConfidence {
    HIGH,
    MEDIUM,
}

data class WorkVariantSuggestion(
    val work: WorkVariant,
    val confidence: WorkVariantSuggestionConfidence,
    val reason: String,
)

data class WorkVariantEditor(
    val source: WorkVariant,
    val group: WorkGroup?,
    val groupMembers: List<WorkVariant>,
    val suggestions: List<WorkVariantSuggestion>,
)
