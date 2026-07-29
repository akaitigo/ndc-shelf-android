package dev.ndcshelf.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "sync_settings")
data class SyncSettingsEntity(
    @androidx.room.PrimaryKey val id: Int = SINGLETON_ID,
    val enabled: Boolean,
    val deviceId: String?,
    val nextCounter: Long,
    val lastSuccessfulAt: Long?,
    val requiresReregistration: Boolean,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "sync_operations",
    primaryKeys = ["operationId"],
    indices = [
        Index(value = ["deviceId", "counter"], unique = true),
        Index(value = ["transactionId"]),
        Index(value = ["state", "deviceId", "counter"]),
    ],
)
data class SyncOperationEntity(
    val operationId: String,
    val deviceId: String,
    val counter: Long,
    val transactionId: String,
    val transactionIndex: Int,
    val transactionSize: Int,
    val entityType: String,
    val entityId: String,
    val kind: String,
    val fieldValuesJson: String,
    val causalContextJson: String,
    val createdAt: Long,
    val state: String,
)

@Entity(
    tableName = "sync_field_states",
    primaryKeys = ["entityType", "entityId", "fieldName"],
    indices = [Index(value = ["winnerDeviceId", "winnerCounter"])],
)
data class SyncFieldStateEntity(
    val entityType: String,
    val entityId: String,
    val fieldName: String,
    val valueJson: String,
    val winnerDeviceId: String,
    val winnerCounter: Long,
    val causalContextJson: String,
)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entityType", "entityId"],
    indices = [Index(value = ["retainUntil"])],
)
data class SyncTombstoneEntity(
    val entityType: String,
    val entityId: String,
    val deletingDeviceId: String,
    val deletingCounter: Long,
    val deletedAt: Long,
    val retainUntil: Long,
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @androidx.room.PrimaryKey val deviceId: String,
    val receivedCounter: Long,
    val processedCounter: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_acknowledgements",
    primaryKeys = ["acknowledgingDeviceId", "observedDeviceId"],
    indices = [Index(value = ["observedDeviceId", "counter"])],
)
data class SyncAcknowledgementEntity(
    val acknowledgingDeviceId: String,
    val observedDeviceId: String,
    val counter: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sync_conflicts",
    indices = [
        Index(value = ["resolvedOperationId", "detectedAt"]),
        Index(value = ["entityType", "entityId"]),
    ],
)
data class SyncConflictEntity(
    @androidx.room.PrimaryKey val id: String,
    val transactionId: String,
    val entityType: String,
    val entityId: String,
    val fieldName: String,
    val winnerValueJson: String,
    val loserValueJson: String,
    val winnerDeviceId: String,
    val winnerCounter: Long,
    val loserDeviceId: String,
    val loserCounter: Long,
    val detectedAt: Long,
    val resolvedOperationId: String?,
)

@Entity(
    tableName = "sync_unresolved_dependencies",
    primaryKeys = ["operationId", "entityType", "entityId"],
    indices = [Index(value = ["entityType", "entityId"])],
)
data class SyncUnresolvedDependencyEntity(
    val operationId: String,
    val entityType: String,
    val entityId: String,
)

data class SyncStatusRow(
    val enabled: Boolean,
    val deviceId: String?,
    val lastSuccessfulAt: Long?,
    val requiresReregistration: Boolean,
    val pendingOperationCount: Int,
    val unresolvedConflictCount: Int,
)
