package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.model.SeriesOverview
import kotlinx.coroutines.flow.Flow

interface SeriesRepository {
    fun observeCatalog(): Flow<List<SeriesOverview>>
}
