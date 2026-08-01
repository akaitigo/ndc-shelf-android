package dev.ndcshelf.app.data.sync.backend

import dev.ndcshelf.app.R
import dev.ndcshelf.app.data.sync.crypto.Base64Url
import dev.ndcshelf.app.data.sync.crypto.SyncCrypto
import dev.ndcshelf.app.domain.sync.MAX_SYNC_OBJECT_PLAINTEXT_BYTES
import dev.ndcshelf.app.domain.sync.SYNC_PROTOCOL_MAJOR
import dev.ndcshelf.app.domain.sync.SYNC_SUITE_ID
import dev.ndcshelf.app.domain.sync.StoredDeviceEnvelope
import dev.ndcshelf.app.domain.sync.StoredJoinRequest
import dev.ndcshelf.app.domain.sync.SyncBackend
import dev.ndcshelf.app.domain.sync.SyncBackendCapabilities
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncBackendException
import dev.ndcshelf.app.domain.sync.SyncCasResult
import dev.ndcshelf.app.domain.sync.SyncDeletionReceipt
import dev.ndcshelf.app.domain.sync.SyncHeadRecord
import dev.ndcshelf.app.domain.text.UiMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAFフォルダ等のuser-selected folderを保存先にする参照backend
 * （ADR 0005の「filesystem/WebDAV相当adapter」）。account認証を持たず、
 * 利用者が選択した保存先を信頼境界とする。objectはcontent-addressedかつ
 * immutable、headは内容hashをetagにしたcompare-and-setで更新する。
 * 共有フォルダの同時書込みに対するCASはlock fileによるbest effortで、
 * 敗者はpullとmergeで再試行する。
 */
class FolderSyncBackend(
    private val store: SyncObjectStore,
    private val libraryOpaqueId: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncBackend {
    @Volatile
    private var lastDeletionReceipt: SyncDeletionReceipt? = null

    override suspend fun getCapabilities(): SyncBackendCapabilities =
        SyncBackendCapabilities(
            protocolMajor = SYNC_PROTOCOL_MAJOR,
            protocolMinor = 0,
            suites = setOf(SYNC_SUITE_ID),
            maxObjectSizeBytes = MAX_OBJECT_BYTES,
            supportsCompareAndSet = true,
            retentionDays = null,
            deletionSlaDays = 0,
            rateLimitPerMinute = null,
            exportAvailable = true,
        )

    override suspend fun createLibrary(
        initialRegistry: ByteArray,
        initialHead: ByteArray,
    ): Unit =
        withContext(dispatcher) {
            if (store.read(headPath()) != null) {
                throw SyncBackendException(
                    SyncBackendErrorKind.CAS_CONFLICT,
                    "A sync library already exists in the selected folder.",
                )
            }
            if (!store.writeIfAbsent(registryPath(1), initialRegistry)) {
                throw SyncBackendException(
                    SyncBackendErrorKind.CAS_CONFLICT,
                    "A sync registry already exists in the selected folder.",
                )
            }
            when (compareAndSetHeadBlocking(null, initialHead)) {
                is SyncCasResult.Committed -> {
                    Unit
                }

                is SyncCasResult.Conflict -> {
                    throw SyncBackendException(
                        SyncBackendErrorKind.CAS_CONFLICT,
                        "The library head was created concurrently.",
                    )
                }
            }
        }

    override suspend fun libraryExists(): Boolean =
        withContext(dispatcher) {
            store.read(headPath()) != null
        }

    override suspend fun getHead(): SyncHeadRecord? =
        withContext(dispatcher) {
            store.read(headPath())?.let { bytes -> SyncHeadRecord(bytes, etagOf(bytes)) }
        }

    override suspend fun compareAndSetHead(
        expectedEtag: String?,
        newHead: ByteArray,
    ): SyncCasResult =
        withContext(dispatcher) {
            compareAndSetHeadBlocking(expectedEtag, newHead)
        }

    private fun compareAndSetHeadBlocking(
        expectedEtag: String?,
        newHead: ByteArray,
    ): SyncCasResult {
        if (!acquireHeadLock()) {
            return SyncCasResult.Conflict(
                store.read(headPath())?.let { SyncHeadRecord(it, etagOf(it)) },
            )
        }
        try {
            val current = store.read(headPath())
            val currentEtag = current?.let(::etagOf)
            if (currentEtag != expectedEtag) {
                return SyncCasResult.Conflict(current?.let { SyncHeadRecord(it, currentEtag.orEmpty()) })
            }
            store.writeReplace(headPath(), newHead)
            return SyncCasResult.Committed(etagOf(newHead))
        } finally {
            store.delete(lockPath())
        }
    }

    private fun acquireHeadLock(): Boolean {
        val payload = nowMillis().toString().toByteArray(Charsets.UTF_8)
        if (store.writeIfAbsent(lockPath(), payload)) return true
        val existing = store.read(lockPath()) ?: return store.writeIfAbsent(lockPath(), payload)
        val lockedAt = existing.toString(Charsets.UTF_8).toLongOrNull() ?: 0
        if (nowMillis() - lockedAt < HEAD_LOCK_STALE_MILLIS) return false
        store.delete(lockPath())
        return store.writeIfAbsent(lockPath(), payload)
    }

    override suspend fun putObjectIfAbsent(
        objectId: String,
        bytes: ByteArray,
    ): Unit =
        withContext(dispatcher) {
            requireValidId(objectId)
            if (bytes.size > MAX_OBJECT_BYTES) {
                throw SyncBackendException(
                    SyncBackendErrorKind.INVALID_RESPONSE,
                    "A sync object exceeds the size limit.",
                )
            }
            // content-addressedのため、既存objectIdへの再uploadは冪等成功。
            store.writeIfAbsent(objectPath(objectId), bytes)
            Unit
        }

    override suspend fun getObject(objectId: String): ByteArray =
        withContext(dispatcher) {
            requireValidId(objectId)
            store.read(objectPath(objectId))
                ?: throw SyncBackendException(
                    SyncBackendErrorKind.NOT_FOUND,
                    "A referenced sync object is missing.",
                )
        }

    override suspend fun putRegistryIfAbsent(
        generation: Int,
        bytes: ByteArray,
    ): Unit =
        withContext(dispatcher) {
            require(generation >= 1)
            store.writeIfAbsent(registryPath(generation), bytes)
            Unit
        }

    override suspend fun getRegistry(generation: Int): ByteArray =
        withContext(dispatcher) {
            require(generation >= 1)
            store.read(registryPath(generation))
                ?: throw SyncBackendException(
                    SyncBackendErrorKind.NOT_FOUND,
                    "A referenced device registry generation is missing.",
                )
        }

    override suspend fun listDeviceEnvelopes(): List<StoredDeviceEnvelope> =
        withContext(dispatcher) {
            store
                .list(listOf(LIBRARY_ROOT, libraryOpaqueId, ENVELOPES_DIR))
                .filter { it.endsWith(FILE_SUFFIX) }
                .mapNotNull { name ->
                    val id = name.removeSuffix(FILE_SUFFIX)
                    store
                        .read(envelopePath(id))
                        ?.let { StoredDeviceEnvelope(id, it) }
                }
        }

    override suspend fun putDeviceEnvelopeIfAuthorized(
        envelopeId: String,
        bytes: ByteArray,
    ): Unit =
        withContext(dispatcher) {
            requireValidId(envelopeId)
            store.writeIfAbsent(envelopePath(envelopeId), bytes)
            Unit
        }

    override suspend fun listJoinRequests(): List<StoredJoinRequest> =
        withContext(dispatcher) {
            store
                .list(listOf(LIBRARY_ROOT, libraryOpaqueId, JOIN_DIR))
                .filter { it.endsWith(FILE_SUFFIX) }
                .mapNotNull { name ->
                    val id = name.removeSuffix(FILE_SUFFIX)
                    store.read(joinPath(id))?.let { StoredJoinRequest(id, it) }
                }
        }

    override suspend fun putJoinRequest(
        requestId: String,
        bytes: ByteArray,
    ): Unit =
        withContext(dispatcher) {
            requireValidId(requestId)
            store.writeReplace(joinPath(requestId), bytes)
        }

    override suspend fun deleteJoinRequest(requestId: String): Unit =
        withContext(dispatcher) {
            requireValidId(requestId)
            store.delete(joinPath(requestId))
            Unit
        }

    override suspend fun requestRemoteDeletion(): SyncDeletionReceipt =
        withContext(dispatcher) {
            val requestedAt = nowMillis()
            val remaining = store.deleteRecursively(listOf(LIBRARY_ROOT, libraryOpaqueId))
            val receipt =
                SyncDeletionReceipt(
                    requestedAtMillis = requestedAt,
                    completedAtMillis = if (remaining == 0) nowMillis() else null,
                    remainingObjectCount = remaining,
                    physicalDeletionNote = PHYSICAL_DELETION_NOTE,
                )
            lastDeletionReceipt = receipt
            receipt
        }

    override suspend fun getDeletionReceipt(): SyncDeletionReceipt? = lastDeletionReceipt

    private fun headPath() = listOf(LIBRARY_ROOT, libraryOpaqueId, "head$FILE_SUFFIX")

    private fun lockPath() = listOf(LIBRARY_ROOT, libraryOpaqueId, "head.lock")

    private fun objectPath(objectId: String) = listOf(LIBRARY_ROOT, libraryOpaqueId, OBJECTS_DIR, "$objectId$FILE_SUFFIX")

    private fun registryPath(generation: Int) = listOf(LIBRARY_ROOT, libraryOpaqueId, REGISTRIES_DIR, "$generation$FILE_SUFFIX")

    private fun envelopePath(envelopeId: String) = listOf(LIBRARY_ROOT, libraryOpaqueId, ENVELOPES_DIR, "$envelopeId$FILE_SUFFIX")

    private fun joinPath(requestId: String) = listOf(LIBRARY_ROOT, libraryOpaqueId, JOIN_DIR, "$requestId$FILE_SUFFIX")

    private fun requireValidId(id: String) {
        if (id.isBlank() || id.length > 100 || !id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw SyncBackendException(SyncBackendErrorKind.INVALID_RESPONSE, "Invalid sync identifier.")
        }
    }

    private fun etagOf(bytes: ByteArray): String = Base64Url.encode(SyncCrypto.sha256(bytes))

    companion object {
        const val LIBRARY_ROOT = "ndc-shelf-sync"
        private const val OBJECTS_DIR = "objects"
        private const val REGISTRIES_DIR = "registries"
        private const val ENVELOPES_DIR = "envelopes"
        private const val JOIN_DIR = "join-requests"
        private const val FILE_SUFFIX = ".json"
        private const val HEAD_LOCK_STALE_MILLIS = 60_000L
        private val PHYSICAL_DELETION_NOTE =
            UiMessage(R.string.sync_purge_receipt_detail)

        /** objectのwire上限。padding込みで8 MiB + envelope overheadを許容する。 */
        const val MAX_OBJECT_BYTES = MAX_SYNC_OBJECT_PLAINTEXT_BYTES + 512L * 1024

        /** 選択フォルダ内の既存libraryを検出する（join用）。複数libraryは非対応。 */
        fun discoverLibrary(store: SyncObjectStore): String? {
            val entries =
                try {
                    store.list(listOf(LIBRARY_ROOT))
                } catch (_: SyncBackendException) {
                    return null
                }
            val candidates =
                entries.filter { name ->
                    name.length in 16..100 && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }
                }
            return candidates.singleOrNull()
        }
    }
}
