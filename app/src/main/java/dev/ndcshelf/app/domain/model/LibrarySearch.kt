package dev.ndcshelf.app.domain.model

data class LibrarySearchCriteria(
    val query: String = "",
    val readingStatus: ReadingStatus? = null,
    val sort: LibrarySort = LibrarySort.ADDED_NEWEST,
    val selectedEditionId: String? = null,
    /** 選択タグを全て含む作品へ絞り込む（AND条件）。上限はTagNameRules.MAX_TAG_FILTERS。 */
    val tagIds: Set<String> = emptySet(),
    /**
     * NDC類（0〜9）での絞り込み。自然言語解釈（NaturalLanguageQueryParser）だけが設定し、
     * 手動UI・保存済み検索の永続化対象には含めない。
     */
    val ndcTopClass: Int? = null,
    /** 置き場所（自由入力location・部屋/本棚/段の名前）への部分一致。自然言語解釈だけが設定する。 */
    val locationQuery: String? = null,
    /** 追加日時の下限（この時刻を含む）。自然言語解釈だけが設定する。 */
    val addedAfterMillis: Long? = null,
    /** 追加日時の上限（この時刻を含まない）。自然言語解釈だけが設定する。 */
    val addedBeforeMillis: Long? = null,
) {
    val normalizedQuery: String
        get() = query.trim().take(MAX_LIBRARY_QUERY_LENGTH)

    val normalizedTagIds: Set<String>
        get() = tagIds.take(TagNameRules.MAX_TAG_FILTERS).toSet()
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
