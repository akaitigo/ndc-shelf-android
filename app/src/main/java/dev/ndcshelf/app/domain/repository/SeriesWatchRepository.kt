package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.model.SeriesWatchOverview
import kotlinx.coroutines.flow.Flow

interface SeriesWatchRepository {
    fun observeWatches(): Flow<List<SeriesWatchOverview>>

    suspend fun setEnabled(seriesId: String, enabled: Boolean): SeriesWatchMutationResult

    suspend fun hasEnabledWatches(): Boolean

    suspend fun checkEnabledWatches(): SeriesWatchCheckResult

    suspend fun markNotified(candidateIds: List<String>)
}

sealed interface SeriesWatchMutationResult {
    data object Updated : SeriesWatchMutationResult
    data object Invalid : SeriesWatchMutationResult
    data object Failure : SeriesWatchMutationResult
}

data class SeriesReleaseNotification(
    val seriesId: String,
    val seriesTitle: String,
    val candidateIds: List<String>,
    val candidateTitles: List<String>,
)

sealed interface SeriesWatchCheckResult {
    data class Success(val notifications: List<SeriesReleaseNotification>) : SeriesWatchCheckResult
    data class PartialFailure(
        val notifications: List<SeriesReleaseNotification>,
        val retryable: Boolean,
    ) : SeriesWatchCheckResult
}

interface SeriesWatchScheduler {
    fun reconcile(enabled: Boolean)
}
