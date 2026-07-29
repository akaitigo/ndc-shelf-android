package dev.ndcshelf.app.domain.sync

import kotlinx.coroutines.flow.Flow

fun interface SyncStatusRepository {
    fun observeStatus(): Flow<SyncEngineStatus>
}
