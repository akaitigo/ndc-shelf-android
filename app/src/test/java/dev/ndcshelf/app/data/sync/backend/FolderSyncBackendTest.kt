package dev.ndcshelf.app.data.sync.backend

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncBackendException
import dev.ndcshelf.app.domain.sync.SyncCasResult
import dev.ndcshelf.app.domain.text.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class FolderSyncBackendTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val libraryOpaqueId = "library-1"

    private fun backend(store: SyncObjectStore = FileSyncObjectStore(folder.root)) =
        FolderSyncBackend(store, libraryOpaqueId, dispatcher = Dispatchers.Unconfined)

    @Test
    fun createLibraryStoresRegistryAndHeadOnce() =
        runBlocking {
            val backend = backend()
            assertFalse(backend.libraryExists())
            backend.createLibrary("registry".toByteArray(), "head".toByteArray())
            assertTrue(backend.libraryExists())
            assertArrayEquals("registry".toByteArray(), backend.getRegistry(1))
            assertArrayEquals("head".toByteArray(), backend.getHead()?.bytes)

            val duplicate =
                assertThrows(SyncBackendException::class.java) {
                    runBlocking { backend.createLibrary("r2".toByteArray(), "h2".toByteArray()) }
                }
            assertEquals(SyncBackendErrorKind.CAS_CONFLICT, duplicate.kind)
        }

    @Test
    fun objectUploadIsIdempotentAndImmutable() =
        runBlocking {
            val backend = backend()
            backend.createLibrary("registry".toByteArray(), "head".toByteArray())
            backend.putObjectIfAbsent("object-a", "payload".toByteArray())
            // 同じIDへの再uploadは冪等成功し、既存bytesを保持する。
            backend.putObjectIfAbsent("object-a", "different".toByteArray())
            assertArrayEquals("payload".toByteArray(), backend.getObject("object-a"))

            val missing =
                assertThrows(SyncBackendException::class.java) {
                    runBlocking { backend.getObject("object-b") }
                }
            assertEquals(SyncBackendErrorKind.NOT_FOUND, missing.kind)

            val invalidId =
                assertThrows(SyncBackendException::class.java) {
                    runBlocking { backend.getObject("../escape") }
                }
            assertEquals(SyncBackendErrorKind.INVALID_RESPONSE, invalidId.kind)
        }

    @Test
    fun compareAndSetHeadDetectsStaleEtag() =
        runBlocking {
            val backend = backend()
            backend.createLibrary("registry".toByteArray(), "head-1".toByteArray())
            val first = requireNotNull(backend.getHead())
            val committed = backend.compareAndSetHead(first.etag, "head-2".toByteArray())
            assertTrue(committed is SyncCasResult.Committed)

            // 古いetagでの更新はconflictになり、内容を書き換えない。
            val conflict = backend.compareAndSetHead(first.etag, "head-3".toByteArray())
            assertTrue(conflict is SyncCasResult.Conflict)
            assertArrayEquals("head-2".toByteArray(), backend.getHead()?.bytes)
            assertNotNull((conflict as SyncCasResult.Conflict).current)
        }

    @Test
    fun envelopesAndJoinRequestsAreListedAndDeleted() =
        runBlocking {
            val backend = backend()
            backend.createLibrary("registry".toByteArray(), "head".toByteArray())
            backend.putDeviceEnvelopeIfAuthorized("env-1", "e1".toByteArray())
            backend.putDeviceEnvelopeIfAuthorized("env-2", "e2".toByteArray())
            assertEquals(
                listOf("env-1", "env-2"),
                backend.listDeviceEnvelopes().map { it.envelopeId },
            )
            backend.putJoinRequest("device-1", "join".toByteArray())
            assertEquals(listOf("device-1"), backend.listJoinRequests().map { it.requestId })
            backend.deleteJoinRequest("device-1")
            assertTrue(backend.listJoinRequests().isEmpty())
        }

    @Test
    fun remoteDeletionRemovesEverythingAndReportsCompletion() =
        runBlocking {
            val backend = backend()
            backend.createLibrary("registry".toByteArray(), "head".toByteArray())
            backend.putObjectIfAbsent("object-a", "payload".toByteArray())
            backend.putDeviceEnvelopeIfAuthorized("env-1", "e1".toByteArray())

            val receipt = backend.requestRemoteDeletion()
            assertEquals(0, receipt.remainingObjectCount)
            assertNotNull(receipt.completedAtMillis)
            assertEquals(
                UiMessage(R.string.sync_purge_receipt_detail),
                receipt.physicalDeletionNote,
            )
            assertFalse(backend.libraryExists())
            assertNull(backend.getHead())
            assertEquals(receipt, backend.getDeletionReceipt())
        }

    @Test
    fun capabilitiesDeclareProtocolAndSuite() =
        runBlocking {
            val capabilities = backend().getCapabilities()
            assertEquals(1, capabilities.protocolMajor)
            assertTrue(capabilities.supportsCompareAndSet)
            assertTrue("P256_HKDF_SHA256_AES256GCM_ECDSA_P256_SHA256" in capabilities.suites)
            assertTrue(capabilities.exportAvailable)
        }

    @Test
    fun ioFailuresAreClassifiedByKind() =
        runBlocking {
            val permissionStore = FailingStore(SecurityException("denied"))
            val permission =
                assertThrows(SyncBackendException::class.java) {
                    runBlocking { backend(permissionStore).getHead() }
                }
            assertEquals(SyncBackendErrorKind.PERMISSION_LOST, permission.kind)
            assertFalse(permission.kind.retryable)

            val fullStore = FailingStore(IOException("ENOSPC: No space left on device"))
            val full =
                assertThrows(SyncBackendException::class.java) {
                    runBlocking { backend(fullStore).getHead() }
                }
            assertEquals(SyncBackendErrorKind.STORAGE_FULL, full.kind)
            assertFalse(full.kind.retryable)

            val ioStore = FailingStore(IOException("transient"))
            val io =
                assertThrows(SyncBackendException::class.java) {
                    runBlocking { backend(ioStore).getHead() }
                }
            assertEquals(SyncBackendErrorKind.IO_FAILURE, io.kind)
            assertTrue(io.kind.retryable)
        }

    @Test
    fun errorKindRetryPolicyMatchesProtocol() {
        // TLS・認証・期限切れ・権限・容量は自動retryしない（10節）。
        listOf(
            SyncBackendErrorKind.TLS_FAILURE,
            SyncBackendErrorKind.AUTHENTICATION_FAILED,
            SyncBackendErrorKind.TOKEN_EXPIRED,
            SyncBackendErrorKind.PERMISSION_LOST,
            SyncBackendErrorKind.STORAGE_FULL,
            SyncBackendErrorKind.INCOMPATIBLE_CAPABILITY,
        ).forEach { kind -> assertFalse(kind.name, kind.retryable) }
        listOf(
            SyncBackendErrorKind.NETWORK,
            SyncBackendErrorKind.RATE_LIMITED,
            SyncBackendErrorKind.SERVICE_UNAVAILABLE,
            SyncBackendErrorKind.IO_FAILURE,
            SyncBackendErrorKind.CAS_CONFLICT,
        ).forEach { kind -> assertTrue(kind.name, kind.retryable) }
    }

    @Test
    fun discoverLibraryFindsASingleLibraryDirectory() {
        val store = FileSyncObjectStore(folder.root)
        assertNull(FolderSyncBackend.discoverLibrary(store))
        runBlocking {
            FolderSyncBackend(store, "0123456789abcdef0123", dispatcher = Dispatchers.Unconfined)
                .createLibrary("registry".toByteArray(), "head".toByteArray())
        }
        assertEquals("0123456789abcdef0123", FolderSyncBackend.discoverLibrary(store))
    }

    private class FailingStore(
        private val error: Exception,
    ) : SyncObjectStore {
        override fun list(directory: List<String>): List<String> = throw wrap()

        override fun read(path: List<String>): ByteArray? = throw wrap()

        override fun writeIfAbsent(
            path: List<String>,
            bytes: ByteArray,
        ): Boolean = throw wrap()

        override fun writeReplace(
            path: List<String>,
            bytes: ByteArray,
        ) = throw wrap()

        override fun delete(path: List<String>): Boolean = throw wrap()

        override fun deleteRecursively(directory: List<String>): Int = throw wrap()

        private fun wrap(): SyncBackendException =
            when (error) {
                is SecurityException -> {
                    SyncBackendException(
                        SyncBackendErrorKind.PERMISSION_LOST,
                        "denied",
                        error,
                    )
                }

                is IOException -> {
                    classifyIo(error)
                }

                else -> {
                    SyncBackendException(SyncBackendErrorKind.IO_FAILURE, "failed", error)
                }
            }
    }
}
