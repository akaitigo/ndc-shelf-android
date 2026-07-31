package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_settings WHERE id = 1")
    suspend fun getSettings(): SyncSettingsEntity?

    @Query("SELECT * FROM sync_settings WHERE id = 1")
    fun observeSettings(): Flow<SyncSettingsEntity?>

    @Upsert
    suspend fun upsertSettings(settings: SyncSettingsEntity)

    @Query(
        "UPDATE sync_settings SET nextCounter = nextCounter + 1 " +
            "WHERE id = 1 AND enabled = 1 AND deviceId IS NOT NULL AND nextCounter < 9223372036854775807",
    )
    suspend fun incrementCounter(): Int

    @Query("SELECT nextCounter FROM sync_settings WHERE id = 1")
    suspend fun getNextCounter(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(operation: SyncOperationEntity): Long

    @Query("SELECT * FROM sync_operations WHERE operationId = :operationId")
    suspend fun findOperation(operationId: String): SyncOperationEntity?

    @Query(
        "SELECT counter FROM sync_operations WHERE deviceId = :deviceId AND counter > :after " +
            "ORDER BY counter LIMIT 1001",
    )
    suspend fun getOperationCountersAfter(
        deviceId: String,
        after: Long,
    ): List<Long>

    @Query(
        "SELECT * FROM sync_operations WHERE state = 'LOCAL_PENDING' " +
            "ORDER BY deviceId, counter LIMIT :limit",
    )
    suspend fun getPendingOperations(limit: Int): List<SyncOperationEntity>

    @Query(
        "SELECT * FROM sync_operations WHERE state = 'REMOTE_PENDING' " +
            "ORDER BY deviceId, counter LIMIT :limit",
    )
    suspend fun getRemotePendingOperations(limit: Int): List<SyncOperationEntity>

    @Query("UPDATE sync_operations SET state = :state WHERE operationId IN (:operationIds)")
    suspend fun updateOperationState(
        operationIds: List<String>,
        state: String,
    ): Int

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state = 'LOCAL_PENDING'")
    fun observePendingOperationCount(): Flow<Int>

    @Query("UPDATE sync_operations SET state = 'ACKNOWLEDGED' WHERE operationId IN (:operationIds)")
    suspend fun markAcknowledged(operationIds: List<String>): Int

    @Query(
        "DELETE FROM sync_operations WHERE state = 'ACKNOWLEDGED' " +
            "AND deviceId = :deviceId AND counter <= :counter",
    )
    suspend fun pruneAcknowledgedOperations(
        deviceId: String,
        counter: Long,
    ): Int

    @Query(
        "SELECT * FROM sync_field_states WHERE entityType = :entityType AND entityId = :entityId",
    )
    suspend fun getFieldStates(
        entityType: String,
        entityId: String,
    ): List<SyncFieldStateEntity>

    @Query(
        "SELECT * FROM sync_field_states WHERE entityType = :entityType " +
            "AND entityId = :entityId AND fieldName = :fieldName",
    )
    suspend fun findFieldState(
        entityType: String,
        entityId: String,
        fieldName: String,
    ): SyncFieldStateEntity?

    @Query("SELECT * FROM sync_field_states ORDER BY entityType, entityId, fieldName")
    suspend fun getAllFieldStates(): List<SyncFieldStateEntity>

    @Upsert
    suspend fun upsertFieldState(state: SyncFieldStateEntity)

    @Query("DELETE FROM sync_field_states WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteFieldStates(
        entityType: String,
        entityId: String,
    ): Int

    @Query("SELECT * FROM sync_tombstones WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun findTombstone(
        entityType: String,
        entityId: String,
    ): SyncTombstoneEntity?

    @Upsert
    suspend fun upsertTombstone(tombstone: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstones WHERE retainUntil <= :nowMillis ORDER BY retainUntil")
    suspend fun getExpiredTombstones(nowMillis: Long): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_tombstones ORDER BY entityType, entityId")
    suspend fun getAllTombstones(): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstones WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteTombstone(
        entityType: String,
        entityId: String,
    ): Int

    @Query("SELECT * FROM sync_cursors WHERE deviceId = :deviceId")
    suspend fun findCursor(deviceId: String): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursors ORDER BY deviceId")
    suspend fun getCursors(): List<SyncCursorEntity>

    @Upsert
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Query(
        "SELECT * FROM sync_acknowledgements WHERE observedDeviceId = :deviceId " +
            "AND acknowledgingDeviceId IN (:activeDeviceIds)",
    )
    suspend fun getAcknowledgements(
        deviceId: String,
        activeDeviceIds: List<String>,
    ): List<SyncAcknowledgementEntity>

    @Upsert
    suspend fun upsertAcknowledgements(acknowledgements: List<SyncAcknowledgementEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConflict(conflict: SyncConflictEntity): Long

    @Query("SELECT * FROM sync_conflicts WHERE id = :conflictId")
    suspend fun findConflict(conflictId: String): SyncConflictEntity?

    @Query(
        "SELECT * FROM sync_conflicts WHERE resolvedOperationId IS NULL " +
            "ORDER BY detectedAt, id",
    )
    suspend fun getUnresolvedConflicts(): List<SyncConflictEntity>

    @Query(
        "SELECT * FROM sync_conflicts WHERE resolvedOperationId IS NULL " +
            "AND entityType = :entityType AND entityId = :entityId AND fieldName = :fieldName",
    )
    suspend fun getUnresolvedFieldConflicts(
        entityType: String,
        entityId: String,
        fieldName: String,
    ): List<SyncConflictEntity>

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE resolvedOperationId IS NULL")
    fun observeUnresolvedConflictCount(): Flow<Int>

    @Query(
        "UPDATE sync_conflicts SET resolvedOperationId = :resolutionOperationId " +
            "WHERE id = :conflictId AND resolvedOperationId IS NULL",
    )
    suspend fun resolveConflict(
        conflictId: String,
        resolutionOperationId: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnresolvedDependencies(dependencies: List<SyncUnresolvedDependencyEntity>)

    @Query("DELETE FROM sync_unresolved_dependencies WHERE operationId = :operationId")
    suspend fun deleteUnresolvedDependencies(operationId: String): Int

    @Query(
        "DELETE FROM sync_unresolved_dependencies " +
            "WHERE entityType = :entityType AND entityId = :entityId",
    )
    suspend fun deleteUnresolvedDependenciesForEntity(
        entityType: String,
        entityId: String,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM sync_unresolved_dependencies " +
            "WHERE entityType = :entityType AND entityId = :entityId",
    )
    suspend fun countUnresolvedDependencies(
        entityType: String,
        entityId: String,
    ): Int

    @Query("DELETE FROM sync_unresolved_dependencies")
    suspend fun deleteAllUnresolvedDependencies()

    @Query("DELETE FROM sync_conflicts")
    suspend fun deleteAllConflicts()

    @Query("DELETE FROM sync_acknowledgements")
    suspend fun deleteAllAcknowledgements()

    @Query("DELETE FROM sync_cursors")
    suspend fun deleteAllCursors()

    @Query("DELETE FROM sync_tombstones")
    suspend fun deleteAllTombstones()

    @Query("DELETE FROM sync_field_states")
    suspend fun deleteAllFieldStates()

    @Query("DELETE FROM sync_operations")
    suspend fun deleteAllOperations()

    @Query("DELETE FROM sync_settings")
    suspend fun deleteAllSettings()
}
