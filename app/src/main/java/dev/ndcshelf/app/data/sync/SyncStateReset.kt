package dev.ndcshelf.app.data.sync

import dev.ndcshelf.app.data.local.SyncDao
import dev.ndcshelf.app.data.local.SyncKeyDao
import dev.ndcshelf.app.data.local.SyncSettingsEntity

internal suspend fun resetSyncStateAfterDomainRestore(
    dao: SyncDao,
    keyDao: SyncKeyDao,
    requiresReregistration: Boolean = true,
) {
    dao.deleteAllUnresolvedDependencies()
    dao.deleteAllConflicts()
    dao.deleteAllAcknowledgements()
    dao.deleteAllCursors()
    dao.deleteAllTombstones()
    dao.deleteAllFieldStates()
    dao.deleteAllOperations()
    dao.deleteAllSettings()
    // 鍵state・registry cache・招待・quarantineも全消去する。restore後は
    // 既存device ID・counterを再利用せず、新deviceとして再登録させる。
    keyDao.deleteIdentity()
    keyDao.deleteWrappedKeys()
    keyDao.deletePeerDevices()
    keyDao.deleteInvites()
    keyDao.deleteProcessedEnvelopes()
    keyDao.deleteQuarantine()
    dao.upsertSettings(
        SyncSettingsEntity(
            enabled = false,
            deviceId = null,
            nextCounter = 0,
            lastSuccessfulAt = null,
            requiresReregistration = requiresReregistration,
        ),
    )
}
