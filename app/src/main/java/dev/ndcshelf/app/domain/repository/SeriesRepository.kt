package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.model.SeriesOverview
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.model.SeriesSuggestion
import kotlinx.coroutines.flow.Flow

interface SeriesRepository {
    fun observeCatalog(): Flow<List<SeriesOverview>>

    fun observeSuggestions(): Flow<List<SeriesSuggestion>>

    suspend fun suggestionFor(workId: String): SeriesSuggestion?

    suspend fun confirm(
        target: SeriesConfirmationTarget,
        drafts: List<SeriesConfirmationDraft>,
    ): SeriesConfirmationResult

    suspend fun removeMembership(membershipId: String): Boolean
}

sealed interface SeriesConfirmationTarget {
    data class Existing(val seriesId: String) : SeriesConfirmationTarget
    data class New(val name: String) : SeriesConfirmationTarget
}

data class SeriesConfirmationDraft(
    val workId: String,
    val volumeLabel: String,
    val type: SeriesMembershipType,
    val sourceTitle: String,
    val origin: SeriesMembershipOrigin,
)

sealed interface SeriesConfirmationResult {
    data class Confirmed(val seriesId: String, val membershipIds: List<String>) :
        SeriesConfirmationResult

    data object Invalid : SeriesConfirmationResult
    data object Conflict : SeriesConfirmationResult
    data object Failure : SeriesConfirmationResult
}
