package dev.ndcshelf.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.domain.sync.SYNC_TOMBSTONE_RETENTION_MILLIS
import dev.ndcshelf.app.domain.sync.SyncDomainApplyResult
import dev.ndcshelf.app.domain.sync.SyncDomainStore
import dev.ndcshelf.app.domain.sync.SyncEntityReference
import dev.ndcshelf.app.domain.sync.SyncMutation
import dev.ndcshelf.app.domain.sync.SyncOperation
import dev.ndcshelf.app.domain.sync.SyncResolvedEntity
import dev.ndcshelf.app.domain.sync.SyncTransport
import dev.ndcshelf.app.domain.sync.SyncVersionVector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSyncEngineIntegrationTest {
    private val databases = mutableListOf<AppDatabase>()

    @Before
    fun setUp() {
        databases.clear()
    }

    @After
    fun tearDown() {
        databases.forEach(AppDatabase::close)
    }

    @Test
    fun disabledEngineDoesNotCreateDeviceOrJournal() = runBlocking {
        val replica = replica("disabled")

        val operations = replica.engine.recordLocalTransaction(
            listOf(upsert("work", "work-1", "title", "端末内だけ")),
        )

        assertTrue(operations.isEmpty())
        assertNull(replica.database.syncDao().getSettings()?.deviceId)
        assertTrue(replica.database.syncDao().getPendingOperations(10).isEmpty())
    }

    @Test
    fun concurrentFieldUpdatesConvergeAndKeepConflictEvidence() = runBlocking {
        val backend = FakeSyncTransport()
        val first = replica("device-a").also { it.engine.initializeDevice("device-a") }
        val second = replica("device-b").also { it.engine.initializeDevice("device-b") }
        first.store.localUpsert("work", "work-1", mapOf("title" to JsonPrimitive("A")))
        second.store.localUpsert("work", "work-1", mapOf("title" to JsonPrimitive("B")))
        first.engine.recordLocalTransaction(listOf(upsert("work", "work-1", "title", "A")))
        second.engine.recordLocalTransaction(listOf(upsert("work", "work-1", "title", "B")))

        first.engine.synchronize(backend)
        second.engine.synchronize(backend)
        first.engine.synchronize(backend)

        assertEquals(JsonPrimitive("B"), first.store.field("work", "work-1", "title"))
        assertEquals(first.store.snapshot(), second.store.snapshot())
        assertEquals(1, first.database.syncDao().getUnresolvedConflicts().size)
        assertEquals(1, second.database.syncDao().getUnresolvedConflicts().size)

        val conflict = first.database.syncDao().getUnresolvedConflicts().single()
        val resolution = first.engine.resolveConflict(conflict.id, JsonPrimitive("C"))

        assertNotNull(resolution)
        assertEquals(JsonPrimitive("C"), first.store.field("work", "work-1", "title"))
        assertTrue(first.database.syncDao().getUnresolvedConflicts().isEmpty())

        first.engine.synchronize(backend)
        second.engine.synchronize(backend)
        assertEquals(JsonPrimitive("C"), second.store.field("work", "work-1", "title"))
        assertTrue(second.database.syncDao().getUnresolvedConflicts().isEmpty())
    }

    @Test
    fun outOfOrderAndDuplicateDeliveryWaitsForCausalityAndAppliesOnce() = runBlocking {
        val source = replica("device-a").also { it.engine.initializeDevice("device-a") }
        val target = replica("device-b").also { it.engine.initializeDevice("device-b") }
        source.store.localUpsert("work", "work-1", mapOf("title" to JsonPrimitive("first")))
        val first = source.engine.recordLocalTransaction(
            listOf(upsert("work", "work-1", "title", "first")),
        ).single()
        source.store.localUpsert("work", "work-1", mapOf("title" to JsonPrimitive("second")))
        val second = source.engine.recordLocalTransaction(
            listOf(upsert("work", "work-1", "title", "second")),
        ).single()

        assertEquals(0, target.engine.ingest(listOf(second, second)))
        assertNull(target.store.field("work", "work-1", "title"))
        assertEquals(2, target.engine.ingest(listOf(first, first)))

        assertEquals(JsonPrimitive("second"), target.store.field("work", "work-1", "title"))
        assertEquals(2L, target.engine.currentProcessedVector()["device-a"])
        assertEquals(2, target.database.syncDao().getOperationCountersAfter("device-a", 0).size)
    }

    @Test
    fun concurrentDeleteAndUpdateConvergesToDeleteDespiteClockSkew() = runBlocking {
        val backend = FakeSyncTransport()
        val firstClock = MutableClock(10L * 365 * 24 * 60 * 60 * 1_000)
        val secondClock = MutableClock(-10L * 365 * 24 * 60 * 60 * 1_000)
        val first = replica("device-a", firstClock::now).also { it.engine.initializeDevice("device-a") }
        val second = replica("device-b", secondClock::now).also { it.engine.initializeDevice("device-b") }
        first.store.localUpsert("work", "work-1", mapOf("title" to JsonPrimitive("base")))
        first.engine.recordLocalTransaction(listOf(upsert("work", "work-1", "title", "base")))
        first.engine.synchronize(backend)
        second.engine.synchronize(backend)

        first.store.localDelete("work", "work-1")
        first.engine.recordLocalTransaction(listOf(SyncMutation.Delete("work", "work-1")))
        second.store.localUpsert("work", "work-1", mapOf("title" to JsonPrimitive("late")))
        second.engine.recordLocalTransaction(listOf(upsert("work", "work-1", "title", "late")))
        first.engine.synchronize(backend)
        second.engine.synchronize(backend)
        first.engine.synchronize(backend)

        assertFalse(first.store.contains("work", "work-1"))
        assertFalse(second.store.contains("work", "work-1"))
        assertNotNull(first.database.syncDao().findTombstone("work", "work-1"))
        assertNotNull(second.database.syncDao().findTombstone("work", "work-1"))
    }

    @Test
    fun tombstoneNeedsRetentionAndEveryActiveDeviceAcknowledgement() = runBlocking {
        val clock = MutableClock(1_000)
        val replica = replica("device-a", clock::now).also { it.engine.initializeDevice("device-a") }
        replica.store.localDelete("work", "work-1")
        replica.engine.recordLocalTransaction(listOf(SyncMutation.Delete("work", "work-1")))
        replica.engine.recordAcknowledgement("device-a", SyncVersionVector(mapOf("device-a" to 1)))

        assertEquals(0, replica.engine.compact(setOf("device-a", "device-b")))
        clock.value += SYNC_TOMBSTONE_RETENTION_MILLIS
        assertEquals(0, replica.engine.compact(setOf("device-a", "device-b")))
        replica.engine.recordAcknowledgement("device-b", SyncVersionVector(mapOf("device-a" to 1)))
        assertEquals(1, replica.engine.compact(setOf("device-a", "device-b")))
        assertNull(replica.database.syncDao().findTombstone("work", "work-1"))
    }

    @Test
    fun domainRestoreResetsCountersAndRequiresNewRegistration() = runBlocking {
        val replica = replica("device-a").also { it.engine.initializeDevice("device-a") }
        replica.engine.recordLocalTransaction(listOf(upsert("work", "work-1", "title", "value")))

        replica.engine.resetAfterDomainRestore()

        val settings = requireNotNull(replica.database.syncDao().getSettings())
        assertFalse(settings.enabled)
        assertNull(settings.deviceId)
        assertEquals(0, settings.nextCounter)
        assertTrue(settings.requiresReregistration)
        assertTrue(replica.database.syncDao().getOperationCountersAfter("device-a", 0).isEmpty())
    }

    @Test
    fun domainConstraintRollsBackTransactionAndKeepsConflictEvidence() = runBlocking {
        val source = replica("device-a").also { it.engine.initializeDevice("device-a") }
        val invalidEdition = BookEditionEntity(
            id = "edition-orphan",
            workId = "missing-work",
            isbn13 = null,
            publisher = null,
            publishedYear = null,
            coverUrl = null,
            ndcCode = null,
            ndcEdition = null,
            classificationSource = "UNKNOWN",
        )
        val operation = source.engine.recordLocalTransaction(
            listOf(invalidEdition.toSyncUpsert()),
        ).single()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)
        val target = RoomSyncEngine(targetDatabase, RoomSyncDomainStore(targetDatabase))
        target.initializeDevice("device-b")

        assertEquals(1, target.ingest(listOf(operation)))

        assertNull(targetDatabase.libraryDao().findEditionById(invalidEdition.id))
        val conflict = targetDatabase.syncDao().getUnresolvedConflicts().single()
        assertEquals("\$domain", conflict.fieldName)
        assertEquals(1L, target.currentProcessedVector()["device-a"])
        assertEquals(
            1,
            targetDatabase.syncDao().countUnresolvedDependencies("edition", invalidEdition.id),
        )
    }

    @Test
    fun olderDeleteDeliveryCannotReplaceNewerTombstoneWinner() = runBlocking {
        val older = replica("device-a").also { it.engine.initializeDevice("device-a") }
        val newer = replica("device-b").also { it.engine.initializeDevice("device-b") }
        val target = replica("device-c").also { it.engine.initializeDevice("device-c") }
        val olderDelete = older.engine.recordLocalTransaction(
            listOf(SyncMutation.Delete("work", "work-1")),
        ).single()
        val newerDelete = newer.engine.recordLocalTransaction(
            listOf(SyncMutation.Delete("work", "work-1")),
        ).single()

        target.engine.ingest(listOf(newerDelete))
        target.engine.ingest(listOf(olderDelete))

        val tombstone = requireNotNull(target.database.syncDao().findTombstone("work", "work-1"))
        assertEquals("device-b", tombstone.deletingDeviceId)
        assertEquals(1L, tombstone.deletingCounter)
    }

    @Test
    fun failedUploadKeepsOutboxForRetry() = runBlocking {
        val replica = replica("device-a").also { it.engine.initializeDevice("device-a") }
        replica.store.localUpsert("work", "work-1", mapOf("title" to JsonPrimitive("offline")))
        replica.engine.recordLocalTransaction(listOf(upsert("work", "work-1", "title", "offline")))
        val backend = FakeSyncTransport()

        runCatching { replica.engine.synchronize(FailingUploadTransport(backend)) }
            .onSuccess { error("The first upload must fail") }
        assertEquals(1, replica.engine.pendingOperations().size)

        replica.engine.synchronize(backend)
        assertTrue(replica.engine.pendingOperations().isEmpty())
    }

    private fun replica(deviceId: String, nowMillis: () -> Long = { 1_000 }): Replica {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)
        val store = InMemorySyncDomainStore()
        return Replica(
            database,
            store,
            RoomSyncEngine(database, store, nowMillis, idFactory = IdSequence(deviceId)),
        )
    }

    private fun upsert(
        entityType: String,
        entityId: String,
        fieldName: String,
        value: String,
    ) = SyncMutation.Upsert(entityType, entityId, mapOf(fieldName to JsonPrimitive(value)))

    private data class Replica(
        val database: AppDatabase,
        val store: InMemorySyncDomainStore,
        val engine: RoomSyncEngine,
    )
}

private class InMemorySyncDomainStore : SyncDomainStore {
    private val entities = linkedMapOf<SyncEntityReference, MutableMap<String, JsonElement>>()

    override suspend fun applyUpserts(entities: List<SyncResolvedEntity>): SyncDomainApplyResult {
        entities.forEach { entity ->
            this.entities.getOrPut(SyncEntityReference(entity.entityType, entity.entityId)) {
                linkedMapOf()
            }.putAll(entity.fields)
        }
        return SyncDomainApplyResult.Applied
    }

    override suspend fun applyDeletes(entities: List<SyncEntityReference>): SyncDomainApplyResult {
        entities.forEach(this.entities::remove)
        return SyncDomainApplyResult.Applied
    }

    fun localUpsert(entityType: String, entityId: String, fields: Map<String, JsonElement>) {
        entities.getOrPut(SyncEntityReference(entityType, entityId)) { linkedMapOf() }.putAll(fields)
    }

    fun localDelete(entityType: String, entityId: String) {
        entities.remove(SyncEntityReference(entityType, entityId))
    }

    fun field(entityType: String, entityId: String, fieldName: String): JsonElement? =
        entities[SyncEntityReference(entityType, entityId)]?.get(fieldName)

    fun contains(entityType: String, entityId: String): Boolean =
        entities.containsKey(SyncEntityReference(entityType, entityId))

    fun snapshot(): Map<SyncEntityReference, Map<String, JsonElement>> =
        entities.mapValues { (_, fields) -> fields.toMap() }
}

private class FakeSyncTransport : SyncTransport {
    private val operations = linkedMapOf<String, SyncOperation>()
    private val acknowledgements = mutableMapOf<String, SyncVersionVector>()

    override suspend fun upload(operations: List<SyncOperation>): Set<String> {
        operations.forEach { operation -> this.operations.putIfAbsent(operation.operationId, operation) }
        return operations.mapTo(linkedSetOf(), SyncOperation::operationId)
    }

    override suspend fun download(after: SyncVersionVector, limit: Int): List<SyncOperation> =
        operations.values.filter { operation -> operation.dot.counter > after[operation.dot.deviceId] }
            .sortedWith(compareBy({ it.dot.deviceId }, { it.dot.counter }))
            .take(limit)

    override suspend fun publishAcknowledgement(deviceId: String, vector: SyncVersionVector) {
        acknowledgements[deviceId] = vector
    }
}

private class FailingUploadTransport(private val delegate: SyncTransport) : SyncTransport by delegate {
    override suspend fun upload(operations: List<SyncOperation>): Set<String> {
        throw IllegalStateException("offline")
    }
}

private class MutableClock(var value: Long) {
    fun now(): Long = value
}

private class IdSequence(private val prefix: String) : () -> String {
    private var next = 0
    override fun invoke(): String = "$prefix-tx-${next++}"
}
