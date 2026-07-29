package dev.ndcshelf.app.data.sync

import dev.ndcshelf.app.data.local.SyncDao
import dev.ndcshelf.app.data.local.SyncSettingsEntity

internal suspend fun resetSyncStateAfterDomainRestore(dao: SyncDao) {
    dao.deleteAllUnresolvedDependencies()
    dao.deleteAllConflicts()
    dao.deleteAllAcknowledgements()
    dao.deleteAllCursors()
    dao.deleteAllTombstones()
    dao.deleteAllFieldStates()
    dao.deleteAllOperations()
    dao.deleteAllSettings()
    dao.upsertSettings(
        SyncSettingsEntity(
            enabled = false,
            deviceId = null,
            nextCounter = 0,
            lastSuccessfulAt = null,
            requiresReregistration = true,
        ),
    )
}
