package dev.ndcshelf.app.data.sync

import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.domain.sync.SyncEngineStatus
import dev.ndcshelf.app.domain.sync.SyncStatusRepository
import kotlinx.coroutines.flow.combine

class RoomSyncStatusRepository(database: AppDatabase) : SyncStatusRepository {
    private val dao = database.syncDao()

    override fun observeStatus() = combine(
        dao.observeSettings(),
        dao.observePendingOperationCount(),
        dao.observeUnresolvedConflictCount(),
    ) { settings, pendingCount, conflictCount ->
        SyncEngineStatus(
            enabled = settings?.enabled == true,
            deviceRegistered = settings?.deviceId != null,
            requiresReregistration = settings?.requiresReregistration == true,
            pendingOperationCount = pendingCount,
            unresolvedConflictCount = conflictCount,
            lastSuccessfulAt = settings?.lastSuccessfulAt,
        )
    }
}
