package dev.ndcshelf.app.data.diagnostics

import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallFailure
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallResult
import dev.ndcshelf.app.domain.ai.llm.LlmModelSource
import dev.ndcshelf.app.domain.ai.llm.LlmModelState
import dev.ndcshelf.app.domain.ai.llm.LlmModelStore
import dev.ndcshelf.app.domain.ai.llm.LlmRuntimeId
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCategory
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticEvent
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DiagnosticsLoggingLlmModelStoreTest {
    private val logged = mutableListOf<DiagnosticCode>()

    private val logger =
        object : DiagnosticsLogger {
            override fun log(code: DiagnosticCode) {
                logged += code
            }

            override fun recentEvents(): List<DiagnosticEvent> = emptyList()

            override fun clearAll() = logged.clear()
        }

    @Test
    fun `successful install is recorded with the allowlisted code`() =
        runTest {
            val store = DiagnosticsLoggingLlmModelStore(FakeStore(LlmModelInstallResult.Installed(File("x"), 1)), logger)

            store.install(definition(), source())

            assertEquals(listOf(DiagnosticCode.LLM_MODEL_INSTALLED), logged)
        }

    @Test
    fun `checksum mismatch and download failures map to distinct codes`() =
        runTest {
            val checksum =
                DiagnosticsLoggingLlmModelStore(
                    FakeStore(LlmModelInstallResult.Failed(LlmModelInstallFailure.CHECKSUM_MISMATCH)),
                    logger,
                )
            checksum.install(definition(), source())
            assertEquals(listOf(DiagnosticCode.LLM_MODEL_CHECKSUM_MISMATCH), logged)

            logged.clear()
            listOf(
                LlmModelInstallFailure.TRANSPORT,
                LlmModelInstallFailure.SIZE_MISMATCH,
                LlmModelInstallFailure.STORAGE_ERROR,
            ).forEach { failure ->
                DiagnosticsLoggingLlmModelStore(FakeStore(LlmModelInstallResult.Failed(failure)), logger)
                    .install(definition(), source())
            }
            assertEquals(List(3) { DiagnosticCode.LLM_MODEL_DOWNLOAD_FAILED }, logged)
        }

    @Test
    fun `failed integrity check is recorded`() {
        val store =
            DiagnosticsLoggingLlmModelStore(
                FakeStore(LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR), verifyResult = false),
                logger,
            )

        assertEquals(false, store.verifyInstalled(definition()))
        assertEquals(listOf(DiagnosticCode.LLM_MODEL_CHECKSUM_MISMATCH), logged)
    }

    @Test
    fun `successful integrity check records nothing`() {
        val store =
            DiagnosticsLoggingLlmModelStore(
                FakeStore(LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)),
                logger,
            )

        assertEquals(true, store.verifyInstalled(definition()))
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `every recorded code belongs to the on device llm category`() =
        runTest {
            LlmModelInstallFailure.entries.forEach { failure ->
                DiagnosticsLoggingLlmModelStore(FakeStore(LlmModelInstallResult.Failed(failure)), logger)
                    .install(definition(), source())
            }

            assertEquals(LlmModelInstallFailure.entries.size, logged.size)
            logged.forEach { code -> assertEquals(DiagnosticCategory.ON_DEVICE_LLM, code.category) }
        }

    private fun source(): LlmModelSource = LlmModelSource { ByteArrayInputStream(ByteArray(0)) }

    private class FakeStore(
        private val result: LlmModelInstallResult,
        private val verifyResult: Boolean = true,
    ) : LlmModelStore {
        override fun state(definition: LlmModelDefinition): LlmModelState = LlmModelState.NotInstalled

        override fun installedFile(definition: LlmModelDefinition): File? = null

        override fun verifyInstalled(definition: LlmModelDefinition): Boolean = verifyResult

        override suspend fun install(
            definition: LlmModelDefinition,
            source: LlmModelSource,
            onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
        ): LlmModelInstallResult = result

        override fun delete(definition: LlmModelDefinition): Boolean = true

        override fun deleteAll(): Boolean = true
    }

    private fun definition(): LlmModelDefinition =
        LlmModelDefinition(
            id = "test-model",
            version = "1.0.0",
            displayName = "テストモデル",
            runtime = LlmRuntimeId.FAKE,
            downloadUrl = "https://huggingface.co/ndc-shelf-test/model/resolve/main/model.bin",
            fileName = "model.bin",
            sizeBytes = 16,
            sha256 = "0".repeat(64),
            licenseSpdxId = "Apache-2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            sourceUrl = "https://huggingface.co/ndc-shelf-test/model",
            verifiedOn = "2026-08-01",
            minSdkInt = 24,
            requiredAbis = setOf("arm64-v8a"),
            minTotalRamBytes = 1,
            requiredFreeBytes = 16,
            contextTokens = 2048,
            addedOn = "2026-08-01",
        )
}
