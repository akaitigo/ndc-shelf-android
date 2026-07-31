package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.core.content.edit
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySearchSettingsStore
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.MAX_LIBRARY_QUERY_LENGTH
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.TagNameRules

class SharedPreferencesLibrarySearchSettingsStore(
    context: Context,
) : LibrarySearchSettingsStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): LibrarySearchCriteria =
        LibrarySearchCriteria(
            query = preferences.getString(KEY_QUERY, "").orEmpty().take(MAX_LIBRARY_QUERY_LENGTH),
            readingStatus = preferences.getString(KEY_STATUS, null)?.toEnumOrNull<ReadingStatus>(),
            sort =
                preferences.getString(KEY_SORT, null)?.toEnumOrNull<LibrarySort>()
                    ?: LibrarySort.ADDED_NEWEST,
            tagIds =
                preferences
                    .getStringSet(KEY_TAG_IDS, emptySet())
                    .orEmpty()
                    .take(TagNameRules.MAX_TAG_FILTERS)
                    .toSet(),
        )

    override fun save(criteria: LibrarySearchCriteria) {
        preferences.edit {
            putString(KEY_QUERY, criteria.normalizedQuery)
            putString(KEY_STATUS, criteria.readingStatus?.name)
            putString(KEY_SORT, criteria.sort.name)
            putStringSet(KEY_TAG_IDS, criteria.normalizedTagIds)
        }
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? = enumValues<T>().firstOrNull { it.name == this }

    private companion object {
        const val PREFERENCES_NAME = "library-search-settings"
        const val KEY_QUERY = "query"
        const val KEY_STATUS = "reading-status"
        const val KEY_SORT = "sort"
        const val KEY_TAG_IDS = "tag-ids"
    }
}
