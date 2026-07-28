package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.model.WorkVariantEditor

interface WorkGroupRepository {
    suspend fun editorFor(workId: String): WorkVariantEditor?

    suspend fun link(
        sourceWorkId: String,
        targetWorkId: String,
        expectedSourceTitle: String,
        expectedTargetTitle: String,
        seriesSubstitutionEnabled: Boolean,
    ): WorkGroupMutationResult

    suspend fun unlink(membershipId: String): WorkGroupMutationResult

    suspend fun setSeriesSubstitution(
        groupId: String,
        enabled: Boolean,
    ): WorkGroupMutationResult
}

sealed interface WorkGroupMutationResult {
    data class Linked(val groupId: String) : WorkGroupMutationResult
    data object Updated : WorkGroupMutationResult
    data object Unlinked : WorkGroupMutationResult
    data object Conflict : WorkGroupMutationResult
    data object Invalid : WorkGroupMutationResult
    data object Failure : WorkGroupMutationResult
}
