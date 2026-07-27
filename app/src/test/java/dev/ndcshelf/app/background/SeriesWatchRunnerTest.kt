package dev.ndcshelf.app.background

import dev.ndcshelf.app.domain.model.SeriesWatchOverview
import dev.ndcshelf.app.domain.repository.SeriesReleaseNotification
import dev.ndcshelf.app.domain.repository.SeriesWatchCheckResult
import dev.ndcshelf.app.domain.repository.SeriesWatchMutationResult
import dev.ndcshelf.app.domain.repository.SeriesWatchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesWatchRunnerTest {
    @Test
    fun permissionDenialDoesNotMarkCandidatesAndRetryableFailureRequestsBackoff() = runBlocking {
        val repository = FakeRepository(
            SeriesWatchCheckResult.PartialFailure(listOf(NOTIFICATION), retryable = true),
        )

        val result = SeriesWatchRunner(repository, SeriesReleaseNotifier { emptySet() }).run()

        assertEquals(SeriesWatchRunResult.RETRY, result)
        assertTrue(repository.marked.isEmpty())
    }

    @Test
    fun onlySuccessfullyPostedCandidateIdsAreMarked() = runBlocking {
        val repository = FakeRepository(SeriesWatchCheckResult.Success(listOf(NOTIFICATION)))

        val result = SeriesWatchRunner(repository, SeriesReleaseNotifier { setOf("candidate") }).run()

        assertEquals(SeriesWatchRunResult.SUCCESS, result)
        assertEquals(listOf("candidate"), repository.marked)
    }

    private class FakeRepository(private val result: SeriesWatchCheckResult) : SeriesWatchRepository {
        val marked = mutableListOf<String>()
        override fun observeWatches(): Flow<List<SeriesWatchOverview>> = emptyFlow()
        override suspend fun setEnabled(seriesId: String, enabled: Boolean) = SeriesWatchMutationResult.Updated
        override suspend fun hasEnabledWatches() = true
        override suspend fun checkEnabledWatches() = result
        override suspend fun markNotified(candidateIds: List<String>) { marked += candidateIds }
    }

    private companion object {
        val NOTIFICATION = SeriesReleaseNotification(
            "series", "年代記", listOf("candidate"), listOf("年代記 2"),
        )
    }
}
