package dev.ndcshelf.app.data.sync

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SyncAcknowledgementEntity
import dev.ndcshelf.app.data.local.SyncConflictEntity
import dev.ndcshelf.app.data.local.SyncCursorEntity
import dev.ndcshelf.app.data.local.SyncFieldStateEntity
import dev.ndcshelf.app.data.local.SyncOperationEntity
import dev.ndcshelf.app.data.local.SyncSettingsEntity
import dev.ndcshelf.app.data.local.SyncTombstoneEntity
import dev.ndcshelf.app.data.local.SyncUnresolvedDependencyEntity
import dev.ndcshelf.app.domain.sync.MAX_DEVICE_ID_LENGTH
import dev.ndcshelf.app.domain.sync.MAX_SYNC_BATCH_OPERATIONS
import dev.ndcshelf.app.domain.sync.MAX_SYNC_DEVICES
import dev.ndcshelf.app.domain.sync.MAX_SYNC_TRANSACTION_OPERATIONS
import dev.ndcshelf.app.domain.sync.SYNC_TOMBSTONE_RETENTION_MILLIS
import dev.ndcshelf.app.domain.sync.SyncDomainApplyResult
import dev.ndcshelf.app.domain.sync.SyncDomainStore
import dev.ndcshelf.app.domain.sync.SyncDot
import dev.ndcshelf.app.domain.sync.SyncEntityReference
import dev.ndcshelf.app.domain.sync.SyncMutation
import dev.ndcshelf.app.domain.sync.SyncMutationJournal
import dev.ndcshelf.app.domain.sync.SyncOperation
import dev.ndcshelf.app.domain.sync.SyncResolvedEntity
import dev.ndcshelf.app.domain.sync.SyncSnapshotData
import dev.ndcshelf.app.domain.sync.SyncSnapshotFieldState
import dev.ndcshelf.app.domain.sync.SyncSnapshotTombstone
import dev.ndcshelf.app.domain.sync.SyncTransport
import dev.ndcshelf.app.domain.sync.SyncVersionVector
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.UUID

class RoomSyncEngine(
    private val database: AppDatabase,
    private val domainStore: SyncDomainStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : SyncMutationJournal {
    private val dao = database.syncDao()

    suspend fun initializeDevice(deviceId: String) =
        database.withTransaction {
            require(deviceId.isNotBlank() && deviceId.length <= MAX_DEVICE_ID_LENGTH)
            val current = dao.getSettings()
            check(current?.enabled != true || current.deviceId == deviceId) {
                "An enabled sync device cannot be replaced without resetting sync state."
            }
            dao.upsertSettings(
                SyncSettingsEntity(
                    enabled = true,
                    deviceId = deviceId,
                    nextCounter = current?.nextCounter ?: 0,
                    lastSuccessfulAt = current?.lastSuccessfulAt,
                    requiresReregistration = false,
                ),
            )
            dao.upsertCursor(
                dao.findCursor(deviceId) ?: SyncCursorEntity(deviceId, 0, 0, nowMillis()),
            )
        }

    suspend fun disable() =
        database.withTransaction {
            val current = dao.getSettings() ?: disabledSettings()
            dao.upsertSettings(current.copy(enabled = false))
        }

    suspend fun resetAfterDomainRestore() =
        database.withTransaction {
            resetSyncStateAfterDomainRestore(dao, database.syncKeyDao())
        }

    /** transport成功でackされた自device operationをACKNOWLEDGEDへ進める。 */
    suspend fun markUploaded(operationIds: List<String>) {
        if (operationIds.isEmpty()) return
        database.withTransaction { dao.markAcknowledged(operationIds) }
    }

    suspend fun markSyncSucceeded() =
        database.withTransaction {
            val settings = dao.getSettings() ?: return@withTransaction
            dao.upsertSettings(settings.copy(lastSuccessfulAt = nowMillis()))
        }

    /** Keystore喪失・失効検出時に同期を停止し、再登録要求を表示する。 */
    suspend fun requireReregistration() =
        database.withTransaction {
            val settings = dao.getSettings() ?: disabledSettings()
            dao.upsertSettings(settings.copy(enabled = false, requiresReregistration = true))
        }

    /** 現在のlocal同期状態からbootstrap snapshotを作る（8.2節）。 */
    suspend fun exportSnapshot(): SyncSnapshotData =
        database.withTransaction {
            SyncSnapshotData(
                fieldStates =
                    dao.getAllFieldStates().map { state ->
                        SyncSnapshotFieldState(
                            entityType = state.entityType,
                            entityId = state.entityId,
                            fieldName = state.fieldName,
                            valueJson = state.valueJson,
                            winner = SyncDot(state.winnerDeviceId, state.winnerCounter),
                            causalContext = SyncJsonCodec.decodeVector(state.causalContextJson),
                        )
                    },
                tombstones =
                    dao.getAllTombstones().map { tombstone ->
                        SyncSnapshotTombstone(
                            entityType = tombstone.entityType,
                            entityId = tombstone.entityId,
                            dot = SyncDot(tombstone.deletingDeviceId, tombstone.deletingCounter),
                            deletedAtMillis = tombstone.deletedAt,
                        )
                    },
                versionVector = currentProcessedVector(),
            )
        }

    /**
     * 新端末がbootstrap snapshotから開始する。field state・tombstone・
     * cursorを取り込み、解決済みentityを単一transactionでdomainへ適用する。
     */
    suspend fun bootstrapFromSnapshot(snapshot: SyncSnapshotData) =
        database.withTransaction {
            val settings = dao.getSettings()
            check(settings?.enabled == true && settings.deviceId != null) {
                "Sync must be initialized before bootstrapping from a snapshot."
            }
            snapshot.tombstones.forEach { tombstone ->
                dao.upsertTombstone(
                    SyncTombstoneEntity(
                        entityType = tombstone.entityType,
                        entityId = tombstone.entityId,
                        deletingDeviceId = tombstone.dot.deviceId,
                        deletingCounter = tombstone.dot.counter,
                        deletedAt = tombstone.deletedAtMillis,
                        retainUntil = safeRetentionDeadline(nowMillis()),
                    ),
                )
            }
            snapshot.fieldStates.forEach { state ->
                dao.upsertFieldState(
                    SyncFieldStateEntity(
                        entityType = state.entityType,
                        entityId = state.entityId,
                        fieldName = state.fieldName,
                        valueJson = state.valueJson,
                        winnerDeviceId = state.winner.deviceId,
                        winnerCounter = state.winner.counter,
                        causalContextJson = SyncJsonCodec.encodeVector(state.causalContext),
                    ),
                )
            }
            val now = nowMillis()
            snapshot.versionVector.counters.forEach { (deviceId, counter) ->
                val cursor = dao.findCursor(deviceId)
                dao.upsertCursor(
                    SyncCursorEntity(
                        deviceId = deviceId,
                        receivedCounter = maxOf(cursor?.receivedCounter ?: 0, counter),
                        processedCounter = maxOf(cursor?.processedCounter ?: 0, counter),
                        updatedAt = now,
                    ),
                )
            }
            val entities =
                snapshot.fieldStates
                    .groupBy { it.entityType to it.entityId }
                    .map { (key, states) ->
                        SyncResolvedEntity(
                            entityType = key.first,
                            entityId = key.second,
                            fields =
                                states.associate { state ->
                                    state.fieldName to
                                        SyncJsonCodec
                                            .decodeFields("{\"value\":${state.valueJson}}")
                                            .getValue("value")
                                },
                        )
                    }
            if (entities.isNotEmpty()) {
                check(domainStore.applyUpserts(entities) == SyncDomainApplyResult.Applied) {
                    "Bootstrap snapshot could not be applied to the domain."
                }
            }
        }

    suspend fun recordLocalTransaction(
        mutations: List<SyncMutation>,
        transactionId: String = idFactory(),
    ): List<SyncOperation> =
        database.withTransaction {
            require(mutations.size in 1..MAX_SYNC_TRANSACTION_OPERATIONS)
            val settings =
                dao.getSettings() ?: disabledSettings().also { disabled ->
                    dao.upsertSettings(disabled)
                }
            if (!settings.enabled || settings.deviceId == null) return@withTransaction emptyList()
            val deviceId = settings.deviceId
            val causalContext = currentProcessedVector()
            val createdAt = nowMillis()
            val operations =
                mutations.mapIndexed { index, mutation ->
                    check(dao.incrementCounter() == 1) { "Sync counter is unavailable or exhausted." }
                    val counter = requireNotNull(dao.getNextCounter())
                    val operation =
                        SyncOperation(
                            operationId = operationId(deviceId, counter),
                            dot = SyncDot(deviceId, counter),
                            transactionId = transactionId,
                            transactionIndex = index,
                            transactionSize = mutations.size,
                            mutation = mutation,
                            causalContext = causalContext,
                            createdAt = createdAt,
                        )
                    check(dao.insertOperation(operation.toEntity(LOCAL_PENDING)) != -1L)
                    applyLocalFieldState(operation)
                    operation
                }
            val lastCounter = operations.last().dot.counter
            dao.upsertCursor(SyncCursorEntity(deviceId, lastCounter, lastCounter, createdAt))
            operations
        }

    override suspend fun record(mutations: List<SyncMutation>) {
        if (mutations.isEmpty()) return
        database.withTransaction {
            mutations.chunked(MAX_SYNC_TRANSACTION_OPERATIONS).forEach { chunk ->
                recordLocalTransaction(chunk)
            }
        }
    }

    override suspend fun hasTombstone(
        entityType: String,
        entityId: String,
    ): Boolean = dao.findTombstone(entityType, entityId) != null

    suspend fun pendingOperations(limit: Int = MAX_SYNC_BATCH_OPERATIONS): List<SyncOperation> {
        require(limit in 1..MAX_SYNC_BATCH_OPERATIONS)
        return dao.getPendingOperations(limit).map(SyncOperationEntity::toDomain)
    }

    suspend fun ingest(operations: List<SyncOperation>): Int {
        require(operations.size <= MAX_SYNC_BATCH_OPERATIONS)
        database.withTransaction {
            operations.forEach { operation ->
                dao.insertOperation(operation.toEntity(REMOTE_PENDING))
            }
            operations.map { it.dot.deviceId }.distinct().forEach { updateReceivedCursor(it) }
        }
        return processPendingTransactions()
    }

    suspend fun synchronize(transport: SyncTransport): Int {
        val settings = dao.getSettings() ?: return 0
        if (!settings.enabled || settings.deviceId == null) return 0
        val pending = pendingOperations()
        if (pending.isNotEmpty()) {
            val acknowledged = transport.upload(pending)
            if (acknowledged.isNotEmpty()) {
                database.withTransaction {
                    dao.markAcknowledged(acknowledged.toList())
                }
            }
        }
        val downloaded = transport.download(currentReceivedVector(), MAX_SYNC_BATCH_OPERATIONS)
        val applied = ingest(downloaded)
        val processed = currentProcessedVector()
        transport.publishAcknowledgement(settings.deviceId, processed)
        database.withTransaction {
            dao.upsertSettings(
                requireNotNull(dao.getSettings()).copy(lastSuccessfulAt = nowMillis()),
            )
        }
        return applied
    }

    suspend fun recordAcknowledgement(
        acknowledgingDeviceId: String,
        vector: SyncVersionVector,
    ) = database.withTransaction {
        val now = nowMillis()
        dao.upsertAcknowledgements(
            vector.counters.map { (observedDeviceId, counter) ->
                SyncAcknowledgementEntity(acknowledgingDeviceId, observedDeviceId, counter, now)
            },
        )
    }

    suspend fun compact(activeDeviceIds: Set<String>): Int =
        database.withTransaction {
            if (activeDeviceIds.isEmpty()) return@withTransaction 0
            require(activeDeviceIds.size <= MAX_SYNC_DEVICES)
            require(activeDeviceIds.all { it.isNotBlank() && it.length <= MAX_DEVICE_ID_LENGTH })
            var removed = 0
            dao.getExpiredTombstones(nowMillis()).forEach { tombstone ->
                val acknowledgements =
                    dao
                        .getAcknowledgements(
                            tombstone.deletingDeviceId,
                            activeDeviceIds.toList(),
                        ).associateBy(SyncAcknowledgementEntity::acknowledgingDeviceId)
                val fullyAcknowledged =
                    activeDeviceIds.all { deviceId ->
                        acknowledgements[deviceId]?.counter?.let { it >= tombstone.deletingCounter } == true
                    }
                if (fullyAcknowledged) {
                    dao.deleteTombstone(tombstone.entityType, tombstone.entityId)
                    dao.pruneAcknowledgedOperations(
                        tombstone.deletingDeviceId,
                        tombstone.deletingCounter,
                    )
                    removed += 1
                }
            }
            removed
        }

    suspend fun resolveConflict(
        conflictId: String,
        selectedValue: JsonPrimitive,
    ): SyncOperation? =
        database.withTransaction {
            val conflict = dao.findConflict(conflictId) ?: return@withTransaction null
            if (conflict.resolvedOperationId != null || conflict.fieldName.startsWith("\$")) {
                return@withTransaction null
            }
            val operation =
                recordLocalTransaction(
                    listOf(
                        SyncMutation.Upsert(
                            conflict.entityType,
                            conflict.entityId,
                            mapOf(conflict.fieldName to selectedValue),
                        ),
                    ),
                ).singleOrNull() ?: return@withTransaction null
            val fields =
                dao.getFieldStates(conflict.entityType, conflict.entityId).associate { state ->
                    state.fieldName to
                        SyncJsonCodec
                            .decodeFields("{\"value\":${state.valueJson}}")
                            .getValue("value")
                }
            check(
                domainStore.applyUpserts(
                    listOf(SyncResolvedEntity(conflict.entityType, conflict.entityId, fields)),
                ) == SyncDomainApplyResult.Applied,
            )
            check(dao.resolveConflict(conflictId, operation.operationId) == 1)
            dao.deleteUnresolvedDependenciesForEntity(conflict.entityType, conflict.entityId)
            operation
        }

    suspend fun currentReceivedVector(): SyncVersionVector =
        SyncVersionVector(
            dao.getCursors().associate { it.deviceId to it.receivedCounter },
        )

    suspend fun currentProcessedVector(): SyncVersionVector =
        SyncVersionVector(
            dao.getCursors().associate { it.deviceId to it.processedCounter },
        )

    private suspend fun processPendingTransactions(): Int {
        var appliedCount = 0
        var progressed: Boolean
        do {
            progressed = false
            val pending = dao.getRemotePendingOperations(MAX_SYNC_BATCH_OPERATIONS)
            pending.groupBy(SyncOperationEntity::transactionId).values.forEach { entities ->
                val operations =
                    entities
                        .map(SyncOperationEntity::toDomain)
                        .sortedBy(SyncOperation::transactionIndex)
                if (!isComplete(operations) || !causalContextAvailable(operations)) return@forEach
                if (applyRemoteTransaction(operations)) {
                    appliedCount += operations.size
                    progressed = true
                }
            }
        } while (progressed)
        return appliedCount
    }

    private suspend fun applyRemoteTransaction(operations: List<SyncOperation>): Boolean {
        return try {
            database.withTransaction {
                val operationIds = operations.map(SyncOperation::operationId)
                val upserts = mutableListOf<SyncResolvedEntity>()
                val deletes = mutableListOf<SyncEntityReference>()
                val fieldUpdates = mutableListOf<SyncFieldStateEntity>()
                val tombstones = mutableListOf<SyncTombstoneEntity>()
                val conflicts = mutableListOf<SyncConflictEntity>()

                operations.forEach { operation ->
                    when (val mutation = operation.mutation) {
                        is SyncMutation.Delete -> {
                            deletes += SyncEntityReference(mutation.entityType, mutation.entityId)
                            tombstones += mergeTombstone(operation)
                        }

                        is SyncMutation.Upsert -> {
                            val tombstone = dao.findTombstone(mutation.entityType, mutation.entityId)
                            if (tombstone != null) {
                                conflicts += tombstoneConflict(operation, tombstone)
                                return@forEach
                            }
                            val merged =
                                dao
                                    .getFieldStates(mutation.entityType, mutation.entityId)
                                    .associateByTo(linkedMapOf(), SyncFieldStateEntity::fieldName)
                            mutation.fields.forEach { (fieldName, incomingValue) ->
                                resolveObservedConflicts(operation, fieldName)
                                val existing = merged[fieldName]
                                val incoming = operation.toFieldState(fieldName, incomingValue.toString())
                                if (existing == null) {
                                    merged[fieldName] = incoming
                                    fieldUpdates += incoming
                                } else {
                                    val decision = decideField(existing, operation)
                                    if (decision.concurrent && existing.valueJson != incoming.valueJson) {
                                        conflicts += fieldConflict(operation, fieldName, existing, incoming, decision.incomingWins)
                                    }
                                    if (decision.incomingWins) {
                                        merged[fieldName] = incoming
                                        fieldUpdates += incoming
                                    }
                                }
                            }
                            upserts +=
                                SyncResolvedEntity(
                                    mutation.entityType,
                                    mutation.entityId,
                                    merged.mapValues { (_, state) ->
                                        SyncJsonCodec
                                            .decodeFields("{\"value\":${state.valueJson}}")
                                            .getValue("value")
                                    },
                                )
                        }
                    }
                }

                val domainResult =
                    when {
                        deletes.isNotEmpty() -> domainStore.applyDeletes(deletes)
                        else -> SyncDomainApplyResult.Applied
                    }.let { deleteResult ->
                        if (deleteResult is SyncDomainApplyResult.Conflict) {
                            deleteResult
                        } else {
                            domainStore.applyUpserts(upserts)
                        }
                    }
                if (domainResult is SyncDomainApplyResult.Conflict) {
                    val representative = operations.first()
                    dao.insertConflict(domainConflict(representative, domainResult.reason))
                    dao.insertUnresolvedDependencies(
                        domainResult.dependencies.map { dependency ->
                            SyncUnresolvedDependencyEntity(
                                representative.operationId,
                                dependency.entityType,
                                dependency.entityId,
                            )
                        },
                    )
                } else {
                    tombstones.forEach { tombstone ->
                        dao.deleteFieldStates(tombstone.entityType, tombstone.entityId)
                        dao.upsertTombstone(tombstone)
                    }
                    fieldUpdates.forEach { fieldUpdate -> dao.upsertFieldState(fieldUpdate) }
                    conflicts.forEach { conflict -> dao.insertConflict(conflict) }
                }
                dao.updateOperationState(operationIds, REMOTE_PROCESSED)
                advanceProcessedCursors(operations)
                true
            }
        } catch (error: SQLiteConstraintException) {
            quarantineDomainConflict(operations, error)
        }
    }

    private suspend fun quarantineDomainConflict(
        operations: List<SyncOperation>,
        error: SQLiteConstraintException,
    ): Boolean =
        database.withTransaction {
            val representative = operations.first()
            dao.insertConflict(
                domainConflict(
                    representative,
                    "Domain constraint rejected transaction: ${error.message.orEmpty()}",
                ),
            )
            dao.insertUnresolvedDependencies(
                operations
                    .map { operation ->
                        SyncUnresolvedDependencyEntity(
                            representative.operationId,
                            operation.mutation.entityType,
                            operation.mutation.entityId,
                        )
                    }.distinct(),
            )
            dao.updateOperationState(operations.map(SyncOperation::operationId), REMOTE_PROCESSED)
            advanceProcessedCursors(operations)
            true
        }

    private suspend fun advanceProcessedCursors(operations: List<SyncOperation>) {
        operations.groupBy { it.dot.deviceId }.forEach { (deviceId, deviceOperations) ->
            val cursor = dao.findCursor(deviceId) ?: SyncCursorEntity(deviceId, 0, 0, nowMillis())
            dao.upsertCursor(
                cursor.copy(
                    processedCounter =
                        maxOf(
                            cursor.processedCounter,
                            deviceOperations.maxOf { it.dot.counter },
                        ),
                    updatedAt = nowMillis(),
                ),
            )
        }
    }

    private suspend fun updateReceivedCursor(deviceId: String) {
        val current = dao.findCursor(deviceId) ?: SyncCursorEntity(deviceId, 0, 0, nowMillis())
        var contiguous = current.receivedCounter
        while (contiguous < Long.MAX_VALUE) {
            val counters = dao.getOperationCountersAfter(deviceId, contiguous)
            val counterSet = counters.toSet()
            while (contiguous < Long.MAX_VALUE && counterSet.contains(contiguous + 1)) contiguous += 1
            if (counters.size < RECEIVED_CURSOR_PAGE_SIZE || contiguous < counters.last()) break
        }
        dao.upsertCursor(current.copy(receivedCounter = contiguous, updatedAt = nowMillis()))
    }

    private suspend fun causalContextAvailable(operations: List<SyncOperation>): Boolean {
        val processed = currentProcessedVector()
        val causalContextSatisfied =
            operations.first().causalContext.counters.all { (deviceId, counter) ->
                processed[deviceId] >= counter
            }
        if (!causalContextSatisfied) return false
        return operations.none { operation ->
            dao.countUnresolvedDependencies(
                operation.mutation.entityType,
                operation.mutation.entityId,
            ) > 0
        }
    }

    private fun isComplete(operations: List<SyncOperation>): Boolean =
        operations.isNotEmpty() &&
            operations.size == operations.first().transactionSize &&
            operations.map(SyncOperation::transactionIndex) == operations.indices.toList() &&
            operations.all { it.transactionSize == operations.size }

    private suspend fun applyLocalFieldState(operation: SyncOperation) {
        when (val mutation = operation.mutation) {
            is SyncMutation.Delete -> {
                dao.deleteFieldStates(mutation.entityType, mutation.entityId)
                dao.upsertTombstone(mergeTombstone(operation))
            }

            is SyncMutation.Upsert -> {
                check(dao.findTombstone(mutation.entityType, mutation.entityId) == null) {
                    "A tombstoned entity ID cannot be reused."
                }
                mutation.fields.forEach { (fieldName, value) ->
                    dao.upsertFieldState(operation.toFieldState(fieldName, value.toString()))
                }
            }
        }
    }

    private fun decideField(
        existing: SyncFieldStateEntity,
        incoming: SyncOperation,
    ): FieldDecision {
        val existingDot = SyncDot(existing.winnerDeviceId, existing.winnerCounter)
        val existingContext = SyncJsonCodec.decodeVector(existing.causalContextJson)
        return when {
            incoming.causalContext.observes(existingDot) -> FieldDecision(incomingWins = true, concurrent = false)
            existingContext.observes(incoming.dot) -> FieldDecision(incomingWins = false, concurrent = false)
            else -> FieldDecision(incoming.dot > existingDot, concurrent = true)
        }
    }

    private suspend fun resolveObservedConflicts(
        operation: SyncOperation,
        fieldName: String,
    ) {
        dao
            .getUnresolvedFieldConflicts(
                operation.mutation.entityType,
                operation.mutation.entityId,
                fieldName,
            ).forEach { conflict ->
                val observesWinner =
                    operation.causalContext.observes(
                        SyncDot(conflict.winnerDeviceId, conflict.winnerCounter),
                    )
                val observesLoser =
                    operation.causalContext.observes(
                        SyncDot(conflict.loserDeviceId, conflict.loserCounter),
                    )
                if (observesWinner && observesLoser) {
                    dao.resolveConflict(conflict.id, operation.operationId)
                    dao.deleteUnresolvedDependenciesForEntity(conflict.entityType, conflict.entityId)
                }
            }
    }

    private fun SyncOperation.toFieldState(
        fieldName: String,
        valueJson: String,
    ) = SyncFieldStateEntity(
        entityType = mutation.entityType,
        entityId = mutation.entityId,
        fieldName = fieldName,
        valueJson = valueJson,
        winnerDeviceId = dot.deviceId,
        winnerCounter = dot.counter,
        causalContextJson = SyncJsonCodec.encodeVector(causalContext),
    )

    private fun SyncOperation.toTombstone() =
        SyncTombstoneEntity(
            entityType = mutation.entityType,
            entityId = mutation.entityId,
            deletingDeviceId = dot.deviceId,
            deletingCounter = dot.counter,
            deletedAt = createdAt,
            retainUntil = safeRetentionDeadline(nowMillis()),
        )

    private suspend fun mergeTombstone(operation: SyncOperation): SyncTombstoneEntity {
        val incoming = operation.toTombstone()
        val existing = dao.findTombstone(incoming.entityType, incoming.entityId) ?: return incoming
        val incomingDot = SyncDot(incoming.deletingDeviceId, incoming.deletingCounter)
        val existingDot = SyncDot(existing.deletingDeviceId, existing.deletingCounter)
        val winner = if (incomingDot > existingDot) incoming else existing
        return winner.copy(retainUntil = maxOf(existing.retainUntil, incoming.retainUntil))
    }

    private fun safeRetentionDeadline(createdAt: Long): Long =
        if (createdAt > Long.MAX_VALUE - SYNC_TOMBSTONE_RETENTION_MILLIS) {
            Long.MAX_VALUE
        } else {
            createdAt + SYNC_TOMBSTONE_RETENTION_MILLIS
        }

    private fun fieldConflict(
        operation: SyncOperation,
        fieldName: String,
        existing: SyncFieldStateEntity,
        incoming: SyncFieldStateEntity,
        incomingWins: Boolean,
    ): SyncConflictEntity {
        val winner = if (incomingWins) incoming else existing
        val loser = if (incomingWins) existing else incoming
        return SyncConflictEntity(
            id = conflictId(operation.operationId, fieldName, loser.winnerDeviceId, loser.winnerCounter),
            transactionId = operation.transactionId,
            entityType = operation.mutation.entityType,
            entityId = operation.mutation.entityId,
            fieldName = fieldName,
            winnerValueJson = winner.valueJson,
            loserValueJson = loser.valueJson,
            winnerDeviceId = winner.winnerDeviceId,
            winnerCounter = winner.winnerCounter,
            loserDeviceId = loser.winnerDeviceId,
            loserCounter = loser.winnerCounter,
            detectedAt = nowMillis(),
            resolvedOperationId = null,
        )
    }

    private fun tombstoneConflict(
        operation: SyncOperation,
        tombstone: SyncTombstoneEntity,
    ) = SyncConflictEntity(
        id = conflictId(operation.operationId, "\$tombstone", operation.dot.deviceId, operation.dot.counter),
        transactionId = operation.transactionId,
        entityType = operation.mutation.entityType,
        entityId = operation.mutation.entityId,
        fieldName = "\$tombstone",
        winnerValueJson = "null",
        loserValueJson = SyncJsonCodec.encodeFields((operation.mutation as SyncMutation.Upsert).fields),
        winnerDeviceId = tombstone.deletingDeviceId,
        winnerCounter = tombstone.deletingCounter,
        loserDeviceId = operation.dot.deviceId,
        loserCounter = operation.dot.counter,
        detectedAt = nowMillis(),
        resolvedOperationId = null,
    )

    private fun domainConflict(
        operation: SyncOperation,
        reason: String,
    ) = SyncConflictEntity(
        id = conflictId(operation.operationId, "\$domain", operation.dot.deviceId, operation.dot.counter),
        transactionId = operation.transactionId,
        entityType = operation.mutation.entityType,
        entityId = operation.mutation.entityId,
        fieldName = "\$domain",
        winnerValueJson = "null",
        loserValueJson = JsonPrimitive(reason.take(500)).toString(),
        winnerDeviceId = "domain",
        winnerCounter = 0,
        loserDeviceId = operation.dot.deviceId,
        loserCounter = operation.dot.counter,
        detectedAt = nowMillis(),
        resolvedOperationId = null,
    )

    private fun disabledSettings(requiresReregistration: Boolean = false) =
        SyncSettingsEntity(
            enabled = false,
            deviceId = null,
            nextCounter = 0,
            lastSuccessfulAt = null,
            requiresReregistration = requiresReregistration,
        )

    private data class FieldDecision(
        val incomingWins: Boolean,
        val concurrent: Boolean,
    )

    private companion object {
        const val LOCAL_PENDING = "LOCAL_PENDING"
        const val REMOTE_PENDING = "REMOTE_PENDING"
        const val REMOTE_PROCESSED = "REMOTE_PROCESSED"
        const val RECEIVED_CURSOR_PAGE_SIZE = 1_001
    }
}

private fun SyncOperation.toEntity(state: String) =
    SyncOperationEntity(
        operationId = operationId,
        deviceId = dot.deviceId,
        counter = dot.counter,
        transactionId = transactionId,
        transactionIndex = transactionIndex,
        transactionSize = transactionSize,
        entityType = mutation.entityType,
        entityId = mutation.entityId,
        kind =
            when (mutation) {
                is SyncMutation.Upsert -> "UPSERT_FIELDS"
                is SyncMutation.Delete -> "DELETE_ENTITY"
            },
        fieldValuesJson =
            when (mutation) {
                is SyncMutation.Upsert -> SyncJsonCodec.encodeFields(mutation.fields)
                is SyncMutation.Delete -> "{}"
            },
        causalContextJson = SyncJsonCodec.encodeVector(causalContext),
        createdAt = createdAt,
        state = state,
    )

private fun SyncOperationEntity.toDomain() =
    SyncOperation(
        operationId = operationId,
        dot = SyncDot(deviceId, counter),
        transactionId = transactionId,
        transactionIndex = transactionIndex,
        transactionSize = transactionSize,
        mutation =
            when (kind) {
                "UPSERT_FIELDS" -> SyncMutation.Upsert(entityType, entityId, SyncJsonCodec.decodeFields(fieldValuesJson))
                "DELETE_ENTITY" -> SyncMutation.Delete(entityType, entityId)
                else -> error("Unknown sync operation kind")
            },
        causalContext = SyncJsonCodec.decodeVector(causalContextJson),
        createdAt = createdAt,
    )

private fun operationId(
    deviceId: String,
    counter: Long,
): String = "$deviceId:$counter"

private fun conflictId(
    operationId: String,
    fieldName: String,
    deviceId: String,
    counter: Long,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("$operationId\u0000$fieldName\u0000$deviceId\u0000$counter".toByteArray())
        .joinToString("") { "%02x".format(it) }
