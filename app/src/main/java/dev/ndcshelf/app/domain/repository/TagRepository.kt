package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.SavedSearch
import dev.ndcshelf.app.domain.model.Tag
import dev.ndcshelf.app.domain.model.TagAssignment
import dev.ndcshelf.app.domain.model.TagColorRole
import dev.ndcshelf.app.domain.model.TagWithUsage
import dev.ndcshelf.app.domain.text.UiMessage
import kotlinx.coroutines.flow.Flow

/**
 * タグ・タグ付与・保存済み検索（検索条件コレクション）の契約。
 *
 * - タグは作品（work）単位で付与し、同一（tagId, workId）の重複付与は1件に正規化する。
 * - 名前はTagNameRulesで正規化・検証し、正規化後の完全一致を重複として拒否する。
 * - タグ数・保存済み検索数には上限を設ける（性能と入力肥大の抑止）。
 * - 変更はSyncMutationJournalへ独立UUIDのupsert / deleteとして記録する。
 */
interface TagRepository {
    fun observeTags(): Flow<List<TagWithUsage>>

    fun observeAssignments(): Flow<List<TagAssignment>>

    fun observeSavedSearches(): Flow<List<SavedSearch>>

    /** エクスポート用の一貫スナップショット。 */
    suspend fun getTagsSnapshot(): List<Tag>

    suspend fun getAssignmentsSnapshot(): List<TagAssignment>

    suspend fun createTag(
        name: String,
        colorRole: TagColorRole,
    ): TagMutationResult

    suspend fun updateTag(
        tagId: String,
        name: String,
        colorRole: TagColorRole,
    ): TagMutationResult

    /** sourceの付与先をtargetへ付け替えて（重複は1件へ）、sourceタグを削除する。 */
    suspend fun mergeTags(
        sourceTagId: String,
        targetTagId: String,
    ): TagMutationResult

    suspend fun deleteTag(tagId: String): TagMutationResult

    /** 対象作品群へタグを一括付与または一括解除する。 */
    suspend fun setTagOnWorks(
        tagId: String,
        workIds: Set<String>,
        assigned: Boolean,
    ): TagAssignmentResult

    suspend fun saveSearch(
        name: String,
        criteria: LibrarySearchCriteria,
    ): SavedSearchMutationResult

    suspend fun renameSavedSearch(
        searchId: String,
        name: String,
    ): SavedSearchMutationResult

    suspend fun deleteSavedSearch(searchId: String): SavedSearchMutationResult
}

sealed interface TagMutationResult {
    data class Done(
        val tag: Tag?,
    ) : TagMutationResult

    data class Invalid(
        val reason: UiMessage,
    ) : TagMutationResult

    data object Duplicate : TagMutationResult

    data object LimitReached : TagMutationResult

    data object NotFound : TagMutationResult

    data object Failure : TagMutationResult
}

sealed interface TagAssignmentResult {
    data class Applied(
        val changedCount: Int,
    ) : TagAssignmentResult

    data object NotFound : TagAssignmentResult

    data object Failure : TagAssignmentResult
}

sealed interface SavedSearchMutationResult {
    data class Done(
        val savedSearch: SavedSearch?,
    ) : SavedSearchMutationResult

    data class Invalid(
        val reason: UiMessage,
    ) : SavedSearchMutationResult

    data object Duplicate : SavedSearchMutationResult

    data object LimitReached : SavedSearchMutationResult

    data object NotFound : SavedSearchMutationResult

    data object Failure : SavedSearchMutationResult
}
