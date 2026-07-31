package dev.ndcshelf.app.data.sync

import dev.ndcshelf.app.data.sync.protocol.SyncWireTime
import dev.ndcshelf.app.data.sync.protocol.WireCounterRange
import dev.ndcshelf.app.data.sync.protocol.WireDot
import dev.ndcshelf.app.data.sync.protocol.WireFieldState
import dev.ndcshelf.app.data.sync.protocol.WireOperation
import dev.ndcshelf.app.data.sync.protocol.WireOperationsPayload
import dev.ndcshelf.app.data.sync.protocol.WireSnapshotPayload
import dev.ndcshelf.app.data.sync.protocol.WireTombstone
import dev.ndcshelf.app.data.sync.protocol.WireTransaction
import dev.ndcshelf.app.domain.sync.MAX_SYNC_TRANSACTION_OPERATIONS
import dev.ndcshelf.app.domain.sync.SyncDot
import dev.ndcshelf.app.domain.sync.SyncMutation
import dev.ndcshelf.app.domain.sync.SyncOperation
import dev.ndcshelf.app.domain.sync.SyncSnapshotData
import dev.ndcshelf.app.domain.sync.SyncSnapshotFieldState
import dev.ndcshelf.app.domain.sync.SyncSnapshotTombstone
import dev.ndcshelf.app.domain.sync.SyncVersionVector
import kotlinx.serialization.json.Json

/** wire形式（SYNC_PROTOCOL.md 5〜6節）とengine内部modelの相互変換。 */
internal object SyncWireConverters {
    private val json = Json { ignoreUnknownKeys = false }

    fun toWireVector(vector: SyncVersionVector): Map<String, String> =
        vector.counters
            .toSortedMap()
            .mapValues { (_, counter) -> counter.toString() }

    fun fromWireVector(vector: Map<String, String>): SyncVersionVector? {
        val counters = HashMap<String, Long>(vector.size)
        vector.forEach { (deviceId, counter) ->
            val value = counter.toLongOrNull() ?: return null
            if (value < 0) return null
            counters[deviceId] = value
        }
        return try {
            SyncVersionVector(counters)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun toWireOperationsPayload(
        deviceId: String,
        previousObjectHash: String?,
        operations: List<SyncOperation>,
        versionVector: SyncVersionVector,
        nowMillis: Long,
    ): WireOperationsPayload {
        val wireOps =
            operations.map { operation ->
                WireOperation(
                    kind =
                        when (operation.mutation) {
                            is SyncMutation.Upsert -> KIND_UPSERT
                            is SyncMutation.Delete -> KIND_DELETE
                        },
                    entityType = operation.mutation.entityType,
                    entityId = operation.mutation.entityId,
                    dot = WireDot(operation.dot.deviceId, operation.dot.counter.toString()),
                    causalContext = toWireVector(operation.causalContext),
                    transactionId = operation.transactionId,
                    transactionIndex = operation.transactionIndex,
                    transactionSize = operation.transactionSize,
                    createdAt = SyncWireTime.encode(operation.createdAt),
                    fields = (operation.mutation as? SyncMutation.Upsert)?.fields,
                )
            }
        val transactions =
            wireOps
                .groupBy(WireOperation::transactionId)
                .map { (transactionId, ops) -> WireTransaction(transactionId, ops) }
        val counters = operations.map { it.dot.counter }
        return WireOperationsPayload(
            previousObjectHash = previousObjectHash,
            deviceId = deviceId,
            counterRange =
                if (counters.isEmpty()) {
                    null
                } else {
                    WireCounterRange(counters.min().toString(), counters.max().toString())
                },
            versionVector = toWireVector(versionVector),
            transactions = transactions,
            createdAt = SyncWireTime.encode(nowMillis),
        )
    }

    /**
     * 受信payloadをengine operationへ変換する。schema違反はnullを返し、
     * 呼び出し側がsecurity quarantineとして扱う。
     */
    fun fromWireOperationsPayload(payload: WireOperationsPayload): List<SyncOperation>? {
        val operations = mutableListOf<SyncOperation>()
        payload.transactions.forEach { transaction ->
            if (transaction.operations.isEmpty()) return null
            if (transaction.operations.size > MAX_SYNC_TRANSACTION_OPERATIONS) return null
            transaction.operations.forEach { operation ->
                if (operation.transactionId != transaction.transactionId) return null
                if (operation.dot.deviceId != payload.deviceId) return null
                val counter = operation.dot.counter.toLongOrNull() ?: return null
                val causalContext = fromWireVector(operation.causalContext) ?: return null
                val createdAt = SyncWireTime.decode(operation.createdAt) ?: return null
                val mutation =
                    when (operation.kind) {
                        KIND_UPSERT -> {
                            val fields = operation.fields ?: return null
                            try {
                                SyncMutation.Upsert(operation.entityType, operation.entityId, fields)
                            } catch (_: IllegalArgumentException) {
                                return null
                            }
                        }

                        KIND_DELETE -> {
                            try {
                                SyncMutation.Delete(operation.entityType, operation.entityId)
                            } catch (_: IllegalArgumentException) {
                                return null
                            }
                        }

                        else -> {
                            return null
                        }
                    }
                val syncOperation =
                    try {
                        SyncOperation(
                            operationId = "${payload.deviceId}:$counter",
                            dot = SyncDot(payload.deviceId, counter),
                            transactionId = transaction.transactionId,
                            transactionIndex = operation.transactionIndex,
                            transactionSize = operation.transactionSize,
                            mutation = mutation,
                            causalContext = causalContext,
                            createdAt = createdAt,
                        )
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                operations += syncOperation
            }
        }
        val range = payload.counterRange
        if (operations.isEmpty()) {
            if (range != null) return null
            return operations
        }
        val counters = operations.map { it.dot.counter }
        if (range == null) return null
        if (range.first.toLongOrNull() != counters.min()) return null
        if (range.last.toLongOrNull() != counters.max()) return null
        if (counters.toSet().size != counters.size) return null
        return operations
    }

    fun toWireSnapshotPayload(
        deviceId: String,
        previousObjectHash: String?,
        snapshot: SyncSnapshotData,
        nowMillis: Long,
    ): WireSnapshotPayload =
        WireSnapshotPayload(
            previousObjectHash = previousObjectHash,
            deviceId = deviceId,
            versionVector = toWireVector(snapshot.versionVector),
            fieldStates =
                snapshot.fieldStates.map { state ->
                    WireFieldState(
                        entityType = state.entityType,
                        entityId = state.entityId,
                        fieldName = state.fieldName,
                        value = json.parseToJsonElement(state.valueJson),
                        winner = WireDot(state.winner.deviceId, state.winner.counter.toString()),
                        causalContext = toWireVector(state.causalContext),
                    )
                },
            tombstones =
                snapshot.tombstones.map { tombstone ->
                    WireTombstone(
                        entityType = tombstone.entityType,
                        entityId = tombstone.entityId,
                        dot = WireDot(tombstone.dot.deviceId, tombstone.dot.counter.toString()),
                        deletedAt = SyncWireTime.encode(tombstone.deletedAtMillis),
                    )
                },
            createdAt = SyncWireTime.encode(nowMillis),
        )

    fun fromWireSnapshotPayload(payload: WireSnapshotPayload): SyncSnapshotData? {
        val vector = fromWireVector(payload.versionVector) ?: return null
        val fieldStates =
            payload.fieldStates.map { state ->
                val counter = state.winner.counter.toLongOrNull() ?: return null
                val causalContext = fromWireVector(state.causalContext) ?: return null
                SyncSnapshotFieldState(
                    entityType = state.entityType,
                    entityId = state.entityId,
                    fieldName = state.fieldName,
                    valueJson = state.value.toString(),
                    winner =
                        try {
                            SyncDot(state.winner.deviceId, counter)
                        } catch (_: IllegalArgumentException) {
                            return null
                        },
                    causalContext = causalContext,
                )
            }
        val tombstones =
            payload.tombstones.map { tombstone ->
                val counter = tombstone.dot.counter.toLongOrNull() ?: return null
                val deletedAt = SyncWireTime.decode(tombstone.deletedAt) ?: return null
                SyncSnapshotTombstone(
                    entityType = tombstone.entityType,
                    entityId = tombstone.entityId,
                    dot =
                        try {
                            SyncDot(tombstone.dot.deviceId, counter)
                        } catch (_: IllegalArgumentException) {
                            return null
                        },
                    deletedAtMillis = deletedAt,
                )
            }
        return SyncSnapshotData(fieldStates, tombstones, vector)
    }

    private const val KIND_UPSERT = "upsertFields"
    private const val KIND_DELETE = "deleteEntity"
}
