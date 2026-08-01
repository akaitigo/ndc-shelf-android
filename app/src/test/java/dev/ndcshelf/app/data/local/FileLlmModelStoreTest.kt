package dev.ndcshelf.app.data.local

import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallFailure
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallResult
import dev.ndcshelf.app.domain.ai.llm.LlmModelSource
import dev.ndcshelf.app.domain.ai.llm.LlmModelState
import dev.ndcshelf.app.domain.ai.llm.LlmRuntimeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

class FileLlmModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: FileLlmModelStore

    private val payload = "端末内モデルのテスト内容".toByteArray()

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("llm-models")
        store = FileLlmModelStore(root) { 1_700_000_000_000 }
    }

    @Test
    fun `install verifies size and checksum before activating the model`() =
        runTest {
            val definition = definition()

            val result = store.install(definition, source(payload))

            val installed = result as LlmModelInstallResult.Installed
            assertEquals(payload.size.toLong(), installed.fileSizeBytes)
            assertTrue(installed.file.isFile)
            assertEquals(installed.file, store.installedFile(definition))
            val state = store.state(definition) as LlmModelState.Installed
            assertEquals(1_700_000_000_000, state.installedAtMillis)
        }

    @Test
    fun `checksum mismatch keeps the model uninstalled and leaves no fragment`() =
        runTest {
            val definition = definition(sha256 = "1".repeat(64))

            val result = store.install(definition, source(payload))

            assertEquals(
                LlmModelInstallFailure.CHECKSUM_MISMATCH,
                (result as LlmModelInstallResult.Failed).reason,
            )
            assertNull(store.installedFile(definition))
            assertNoStagingLeftovers()
        }

    @Test
    fun `oversized download is stopped at the declared size`() =
        runTest {
            val definition = definition(sizeBytes = 4)

            val result = store.install(definition, source(payload))

            assertEquals(LlmModelInstallFailure.SIZE_MISMATCH, (result as LlmModelInstallResult.Failed).reason)
            assertNull(store.installedFile(definition))
            assertNoStagingLeftovers()
        }

    @Test
    fun `short download is rejected as a size mismatch`() =
        runTest {
            val definition = definition(sizeBytes = payload.size + 1L, sha256 = sha256(payload))

            val result = store.install(definition, source(payload))

            assertEquals(LlmModelInstallFailure.SIZE_MISMATCH, (result as LlmModelInstallResult.Failed).reason)
            assertNull(store.installedFile(definition))
        }

    @Test
    fun `transport failure keeps the previously verified model`() =
        runTest {
            val definition = definition()
            store.install(definition, source(payload))
            val before = store.installedFile(definition)?.readBytes()

            val result =
                store.install(
                    definition,
                    LlmModelSource {
                        object : InputStream() {
                            override fun read(): Int = throw IOException("network dropped")
                        }
                    },
                )

            assertEquals(LlmModelInstallFailure.TRANSPORT, (result as LlmModelInstallResult.Failed).reason)
            assertTrue(before.contentEquals(store.installedFile(definition)?.readBytes()))
            assertNoStagingLeftovers()
        }

    @Test
    fun `cancellation deletes the partial file and rethrows`() =
        runTest {
            val definition = definition(sizeBytes = 1_000_000)

            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    store.install(
                        definition,
                        LlmModelSource {
                            object : InputStream() {
                                override fun read(): Int = 0

                                override fun read(
                                    buffer: ByteArray,
                                    offset: Int,
                                    length: Int,
                                ): Int = throw CancellationException("user cancelled")
                            }
                        },
                    )
                }
            }

            assertNull(store.installedFile(definition))
            assertNoStagingLeftovers()
        }

    @Test
    fun `model update replaces the previous version only after success`() =
        runTest {
            val v1 = definition(version = "1.0.0")
            store.install(v1, source(payload))
            val newPayload = "新しいモデル内容".toByteArray()
            val v2 = definition(version = "2.0.0", sizeBytes = newPayload.size.toLong(), sha256 = sha256(newPayload))

            // 失敗した更新は旧versionを消さない。
            val failed = definition(version = "2.0.0", sizeBytes = newPayload.size.toLong(), sha256 = "2".repeat(64))
            store.install(failed, source(newPayload))
            assertTrue(store.installedFile(v1)?.isFile == true)

            val result = store.install(v2, source(newPayload))

            assertTrue(result is LlmModelInstallResult.Installed)
            assertNull(store.installedFile(v1))
            assertEquals(LlmModelState.NotInstalled, store.state(v1))
            assertTrue(store.installedFile(v2)?.isFile == true)
        }

    @Test
    fun `verifyInstalled removes a tampered model`() =
        runTest {
            val definition = definition()
            store.install(definition, source(payload))
            assertTrue(store.verifyInstalled(definition))

            val file = requireNotNull(store.installedFile(definition))
            file.writeBytes(ByteArray(payload.size) { 0 })

            assertFalse(store.verifyInstalled(definition))
            assertNull(store.installedFile(definition))
        }

    @Test
    fun `deleteAll removes every model and derived file`() =
        runTest {
            val definition = definition()
            store.install(definition, source(payload))
            assertTrue(root.walkTopDown().any { file -> file.isFile })

            assertTrue(store.deleteAll())

            assertFalse(root.exists())
            assertNull(store.installedFile(definition))
            assertEquals(LlmModelState.NotInstalled, store.state(definition))
        }

    @Test
    fun `delete removes a single model directory`() =
        runTest {
            val definition = definition()
            store.install(definition, source(payload))

            assertTrue(store.delete(definition))

            assertNull(store.installedFile(definition))
            assertFalse(File(root, definition.id).exists())
        }

    @Test
    fun `progress is reported against the declared size`() =
        runTest {
            val definition = definition()
            val progress = mutableListOf<Pair<Long, Long>>()

            store.install(definition, source(payload)) { written, total -> progress += written to total }

            assertTrue(progress.isNotEmpty())
            assertEquals(payload.size.toLong(), progress.last().first)
            assertEquals(definition.sizeBytes, progress.last().second)
        }

    private fun assertNoStagingLeftovers() {
        val staging = File(root, ".staging")
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    private fun source(bytes: ByteArray): LlmModelSource = LlmModelSource { ByteArrayInputStream(bytes) }

    private fun definition(
        version: String = "1.0.0",
        sizeBytes: Long = payload.size.toLong(),
        sha256: String = sha256(payload),
    ): LlmModelDefinition =
        LlmModelDefinition(
            id = "test-model",
            version = version,
            displayName = "テストモデル",
            runtime = LlmRuntimeId.FAKE,
            downloadUrl = "https://huggingface.co/ndc-shelf-test/model/resolve/main/model.bin",
            fileName = "model.bin",
            sizeBytes = sizeBytes,
            sha256 = sha256,
            licenseSpdxId = "Apache-2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            sourceUrl = "https://huggingface.co/ndc-shelf-test/model",
            verifiedOn = "2026-08-01",
            minSdkInt = 24,
            requiredAbis = setOf("arm64-v8a"),
            minTotalRamBytes = 1,
            requiredFreeBytes = sizeBytes,
            contextTokens = 2048,
            addedOn = "2026-08-01",
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> String.format(Locale.US, "%02x", byte) }
}
