package dev.ndcshelf.app.domain.model

data class LibrarySearchCriteria(
    val query: String = "",
    val readingStatus: ReadingStatus? = null,
    val sort: LibrarySort = LibrarySort.ADDED_NEWEST,
    val selectedEditionId: String? = null,
) {
    val normalizedQuery: String
        get() = query.trim().take(MAX_LIBRARY_QUERY_LENGTH)
}

enum class LibrarySort {
    ADDED_NEWEST,
    TITLE,
    AUTHOR,
    NDC,
    SHELF,
}

data class LibraryStats(
    val totalCount: Int = 0,
    val classifiedCount: Int = 0,
    val readingCount: Int = 0,
)

const val MAX_LIBRARY_QUERY_LENGTH = 100

interface LibrarySearchSettingsStore {
    fun load(): LibrarySearchCriteria = LibrarySearchCriteria()

    fun save(criteria: LibrarySearchCriteria) = Unit
}

object InMemoryLibrarySearchSettingsStore : LibrarySearchSettingsStore
