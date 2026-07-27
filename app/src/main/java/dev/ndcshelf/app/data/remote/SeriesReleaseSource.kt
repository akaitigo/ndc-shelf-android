package dev.ndcshelf.app.data.remote

data class SeriesReleaseSourceCandidate(
    val sourceRecordId: String,
    val title: String,
    val primaryAuthor: String,
    val isbn13: String?,
    val publisher: String?,
    val publishedDate: String?,
)

fun interface SeriesReleaseSource {
    suspend fun search(queryTitle: String, fromYear: Int): SeriesReleaseSourceResult
}

sealed interface SeriesReleaseSourceResult {
    data class Found(val candidates: List<SeriesReleaseSourceCandidate>) : SeriesReleaseSourceResult
    data class Failure(val reason: SeriesReleaseSourceFailure) : SeriesReleaseSourceResult
}

enum class SeriesReleaseSourceFailure(val retryable: Boolean) {
    OFFLINE(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    SERVER(true),
    NETWORK(true),
    CLIENT(false),
    PARSE(false),
}
