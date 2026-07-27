package dev.ndcshelf.app.domain.model

data class SeriesWatch(
    val seriesId: String,
    val queryTitle: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastCheckedAt: Long?,
    val lastSuccessfulAt: Long?,
)

enum class SeriesReleaseState {
    NEW,
    WANTED,
    RESERVED,
    OWNED,
}

data class SeriesReleaseCandidate(
    val id: String,
    val seriesId: String,
    val title: String,
    val primaryAuthor: String,
    val isbn13: String?,
    val publisher: String?,
    val publishedDate: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val notifiedAt: Long?,
    val state: SeriesReleaseState,
)

data class SeriesWatchOverview(
    val watch: SeriesWatch,
    val candidates: List<SeriesReleaseCandidate>,
)
