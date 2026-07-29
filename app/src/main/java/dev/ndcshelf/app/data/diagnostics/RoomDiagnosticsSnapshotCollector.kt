package dev.ndcshelf.app.data.diagnostics

import androidx.room.Dao
import androidx.room.Query
import dev.ndcshelf.app.data.local.APP_DATABASE_VERSION
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsSnapshot

/** 既存テーブルのCOUNTだけを読む。schema変更を伴わない。 */
@Dao
interface DiagnosticsDao {
    @Query("SELECT COUNT(*) FROM book_works")
    suspend fun countWorks(): Int

    @Query("SELECT COUNT(*) FROM book_editions")
    suspend fun countEditions(): Int

    @Query("SELECT COUNT(*) FROM owned_copies")
    suspend fun countCopies(): Int

    @Query("SELECT COUNT(*) FROM series")
    suspend fun countSeries(): Int

    @Query("SELECT COUNT(*) FROM scan_sessions")
    suspend fun countScanSessions(): Int

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state = 'LOCAL_PENDING'")
    suspend fun countPendingSyncOperations(): Int

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE resolvedOperationId IS NULL")
    suspend fun countUnresolvedSyncConflicts(): Int

    @Query("SELECT enabled FROM sync_settings WHERE id = 1")
    suspend fun isSyncEnabled(): Boolean?

    @Query("SELECT lastSuccessfulAt FROM sync_settings WHERE id = 1")
    suspend fun syncLastSuccessfulAt(): Long?
}

class RoomDiagnosticsSnapshotCollector(
    private val database: AppDatabase,
    private val consentRepository: ConsentRepository,
    private val logger: DiagnosticsLogger,
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val androidSdkInt: Int,
) {
    suspend fun collect(): DiagnosticsSnapshot {
        val dao = database.diagnosticsDao()
        return DiagnosticsSnapshot(
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            androidSdkInt = androidSdkInt,
            databaseVersion = APP_DATABASE_VERSION,
            workCount = dao.countWorks(),
            editionCount = dao.countEditions(),
            copyCount = dao.countCopies(),
            seriesCount = dao.countSeries(),
            scanSessionCount = dao.countScanSessions(),
            syncEnabled = dao.isSyncEnabled() == true,
            syncPendingOperations = dao.countPendingSyncOperations(),
            syncUnresolvedConflicts = dao.countUnresolvedSyncConflicts(),
            syncLastSuccessAtMillis = dao.syncLastSuccessfulAt(),
            consentedPurposes =
                ConsentPurpose.entries
                    .filter { purpose -> consentRepository.isGranted(purpose) }
                    .map(ConsentPurpose::name),
            recentEvents = logger.recentEvents(),
        )
    }
}
