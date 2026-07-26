package dev.ndcshelf.app.data.remote

data class BookMetadata(
    val title: String,
    val authors: List<String>,
    val publisher: String?,
    val publishedYear: Int?,
    val editionStatement: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val coverUrl: String?,
)

fun interface BookMetadataService {
    suspend fun findByIsbn(isbn13: String): BookMetadataLookupResult
}

sealed interface BookMetadataLookupResult {
    data class Found(val metadata: BookMetadata) : BookMetadataLookupResult

    data object NotFound : BookMetadataLookupResult

    data class Failure(
        val reason: BookMetadataFailure,
        val httpStatus: Int? = null,
    ) : BookMetadataLookupResult
}

enum class BookMetadataFailure(val retryable: Boolean) {
    OFFLINE(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    SERVER(true),
    NETWORK(true),
    CLIENT(false),
    PARSE(false),
}
