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
    suspend fun findByIsbn(isbn13: String): BookMetadata?
}
