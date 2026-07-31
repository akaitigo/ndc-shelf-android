package dev.ndcshelf.app.domain.sync

/** bootstrap snapshotのfield state（SYNC_PROTOCOL.md 8.2）。 */
data class SyncSnapshotFieldState(
    val entityType: String,
    val entityId: String,
    val fieldName: String,
    val valueJson: String,
    val winner: SyncDot,
    val causalContext: SyncVersionVector,
)

data class SyncSnapshotTombstone(
    val entityType: String,
    val entityId: String,
    val dot: SyncDot,
    val deletedAtMillis: Long,
)

/** 新端末が開始点にするlibrary状態。current epochで暗号化して配布する。 */
data class SyncSnapshotData(
    val fieldStates: List<SyncSnapshotFieldState>,
    val tombstones: List<SyncSnapshotTombstone>,
    val versionVector: SyncVersionVector,
)
