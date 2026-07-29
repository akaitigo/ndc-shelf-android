package dev.ndcshelf.app.domain.sync

import kotlinx.serialization.json.JsonElement

data class SyncDot(
    val deviceId: String,
    val counter: Long,
) : Comparable<SyncDot> {
    init {
        require(deviceId.isNotBlank() && deviceId.length <= MAX_DEVICE_ID_LENGTH)
        require(counter > 0)
    }

    override fun compareTo(other: SyncDot): Int {
        val deviceComparison = compareBytewise(deviceId, other.deviceId)
        return if (deviceComparison != 0) deviceComparison else counter.compareTo(other.counter)
    }
}

data class SyncVersionVector(
    val counters: Map<String, Long> = emptyMap(),
) {
    init {
        require(counters.size <= MAX_SYNC_DEVICES)
        require(
            counters.all { (deviceId, counter) ->
                deviceId.isNotBlank() && deviceId.length <= MAX_DEVICE_ID_LENGTH && counter >= 0
            },
        )
    }

    operator fun get(deviceId: String): Long = counters[deviceId] ?: 0

    fun observes(dot: SyncDot): Boolean = this[dot.deviceId] >= dot.counter

    fun advanced(dot: SyncDot): SyncVersionVector =
        SyncVersionVector(
            counters + (dot.deviceId to maxOf(this[dot.deviceId], dot.counter)),
        )
}

sealed interface SyncMutation {
    val entityType: String
    val entityId: String

    data class Upsert(
        override val entityType: String,
        override val entityId: String,
        val fields: Map<String, JsonElement>,
    ) : SyncMutation

    data class Delete(
        override val entityType: String,
        override val entityId: String,
    ) : SyncMutation
}

data class SyncOperation(
    val operationId: String,
    val dot: SyncDot,
    val transactionId: String,
    val transactionIndex: Int,
    val transactionSize: Int,
    val mutation: SyncMutation,
    val causalContext: SyncVersionVector,
    val createdAt: Long,
) {
    init {
        require(operationId.isNotBlank() && operationId.length <= MAX_OPERATION_ID_LENGTH)
        require(transactionId.isNotBlank() && transactionId.length <= MAX_TRANSACTION_ID_LENGTH)
        require(transactionSize in 1..MAX_SYNC_TRANSACTION_OPERATIONS)
        require(transactionIndex in 0 until transactionSize)
        require(mutation.entityType in SYNC_ENTITY_TYPES)
        require(mutation.entityId.isNotBlank() && mutation.entityId.length <= MAX_ENTITY_ID_LENGTH)
        if (mutation is SyncMutation.Upsert) {
            require(mutation.fields.isNotEmpty() && mutation.fields.size <= MAX_SYNC_FIELDS)
            require(mutation.fields.keys.all { it.isNotBlank() && it.length <= MAX_FIELD_NAME_LENGTH })
        }
    }
}

data class SyncResolvedEntity(
    val entityType: String,
    val entityId: String,
    val fields: Map<String, JsonElement>,
)

sealed interface SyncDomainApplyResult {
    data object Applied : SyncDomainApplyResult

    data class Conflict(
        val reason: String,
        val dependencies: Set<SyncEntityReference>,
    ) : SyncDomainApplyResult
}

data class SyncEntityReference(
    val entityType: String,
    val entityId: String,
)

interface SyncDomainStore {
    suspend fun applyUpserts(entities: List<SyncResolvedEntity>): SyncDomainApplyResult

    suspend fun applyDeletes(entities: List<SyncEntityReference>): SyncDomainApplyResult
}

interface SyncMutationJournal {
    suspend fun record(mutations: List<SyncMutation>)

    suspend fun hasTombstone(
        entityType: String,
        entityId: String,
    ): Boolean = false

    companion object {
        val Disabled =
            object : SyncMutationJournal {
                override suspend fun record(mutations: List<SyncMutation>) = Unit
            }
    }
}

interface SyncTransport {
    suspend fun upload(operations: List<SyncOperation>): Set<String>

    suspend fun download(
        after: SyncVersionVector,
        limit: Int,
    ): List<SyncOperation>

    suspend fun publishAcknowledgement(
        deviceId: String,
        vector: SyncVersionVector,
    )
}

data class SyncEngineStatus(
    val enabled: Boolean = false,
    val deviceRegistered: Boolean = false,
    val requiresReregistration: Boolean = false,
    val pendingOperationCount: Int = 0,
    val unresolvedConflictCount: Int = 0,
    val lastSuccessfulAt: Long? = null,
)

const val SYNC_TOMBSTONE_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1_000
const val MAX_SYNC_TRANSACTION_OPERATIONS = 1_000
const val MAX_SYNC_BATCH_OPERATIONS = 1_000
const val MAX_SYNC_DEVICES = 100
const val MAX_SYNC_FIELDS = 100
const val MAX_DEVICE_ID_LENGTH = 128
const val MAX_OPERATION_ID_LENGTH = 300
const val MAX_TRANSACTION_ID_LENGTH = 128
const val MAX_ENTITY_ID_LENGTH = 200
const val MAX_FIELD_NAME_LENGTH = 100

val SYNC_ENTITY_TYPES =
    setOf(
        "work",
        "edition",
        "ownedCopy",
        "wishlistItem",
        "locationRoom",
        "locationShelf",
        "locationTier",
        "series",
        "seriesMembership",
        "workGroup",
        "workGroupMembership",
        "readingSession",
    )

private fun compareBytewise(
    first: String,
    second: String,
): Int {
    val firstBytes = first.toByteArray(Charsets.UTF_8)
    val secondBytes = second.toByteArray(Charsets.UTF_8)
    val length = minOf(firstBytes.size, secondBytes.size)
    for (index in 0 until length) {
        val comparison =
            (firstBytes[index].toInt() and 0xff)
                .compareTo(secondBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return firstBytes.size.compareTo(secondBytes.size)
}
