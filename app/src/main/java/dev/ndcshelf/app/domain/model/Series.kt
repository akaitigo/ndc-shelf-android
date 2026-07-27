package dev.ndcshelf.app.domain.model

data class BookSeries(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SeriesMembership(
    val id: String,
    val seriesId: String,
    val workId: String,
    val workTitle: String,
    val primaryAuthor: String,
    val sortOrderKey: String,
    val volumeLabel: String,
    val type: SeriesMembershipType,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class SeriesMembershipType {
    MAIN_STORY,
    SIDE_STORY,
    OMNIBUS,
    OTHER,
}
