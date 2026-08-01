package dev.ndcshelf.app

import dev.ndcshelf.app.domain.ai.llm.LlmCapability
import dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog
import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallFailure
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallResult
import dev.ndcshelf.app.domain.ai.llm.LlmModelSource
import dev.ndcshelf.app.domain.ai.llm.LlmModelState
import dev.ndcshelf.app.domain.ai.llm.LlmModelStore
import dev.ndcshelf.app.domain.ai.llm.LlmUnsupportedReason
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import dev.ndcshelf.app.domain.consent.ConsentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LlmModelViewModelTest {
    private val model = requireNotNull(LlmModelCatalog.defaultModel)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `download never starts without consent`() =
        runTest {
            val store = RecordingStore()
            val viewModel = viewModel(store, supported = true, consented = false)

            viewModel.startDownload()
            advanceUntilIdle()

            assertEquals(0, store.installCount)
            assertEquals(LlmModelFailure.NOT_CONSENTED, viewModel.state.value.failure)
        }

    @Test
    fun `download never starts on an unsupported device`() =
        runTest {
            val store = RecordingStore()
            val viewModel = viewModel(store, supported = false, consented = true)

            viewModel.startDownload()
            advanceUntilIdle()

            assertEquals(0, store.installCount)
            assertEquals(LlmModelFailure.DEVICE_UNSUPPORTED, viewModel.state.value.failure)
            assertFalse(viewModel.state.value.supported)
            assertTrue(
                LlmUnsupportedReason.RUNTIME_UNAVAILABLE in viewModel.state.value.unsupportedReasons,
            )
        }

    @Test
    fun `consented download on a supported device installs the model`() =
        runTest {
            val store = RecordingStore(result = LlmModelInstallResult.Installed(File("model.bin"), 10))
            val viewModel = viewModel(store, supported = true, consented = true)

            viewModel.startDownload()
            advanceUntilIdle()

            assertEquals(1, store.installCount)
            assertNull(viewModel.state.value.failure)
            assertFalse(viewModel.state.value.installing)
        }

    @Test
    fun `checksum mismatch surfaces a distinct reason and keeps nothing installed`() =
        runTest {
            val store =
                RecordingStore(
                    result = LlmModelInstallResult.Failed(LlmModelInstallFailure.CHECKSUM_MISMATCH),
                )
            val viewModel = viewModel(store, supported = true, consented = true)

            viewModel.startDownload()
            advanceUntilIdle()

            assertEquals(LlmModelFailure.CHECKSUM_MISMATCH, viewModel.state.value.failure)
            assertFalse(viewModel.state.value.installed)
        }

    @Test
    fun `transport failure is reported without discarding the installed model`() =
        runTest {
            val store =
                RecordingStore(
                    result = LlmModelInstallResult.Failed(LlmModelInstallFailure.TRANSPORT),
                    installed = true,
                )
            val viewModel = viewModel(store, supported = true, consented = true)

            viewModel.startDownload()
            advanceUntilIdle()

            assertEquals(LlmModelFailure.TRANSPORT, viewModel.state.value.failure)
            assertTrue(viewModel.state.value.installed)
        }

    @Test
    fun `import from the device does not require download consent`() =
        runTest {
            val store = RecordingStore(result = LlmModelInstallResult.Installed(File("model.bin"), 10))
            val viewModel = viewModel(store, supported = true, consented = false)

            viewModel.importFromDevice { ByteArrayInputStream(ByteArray(0)) }
            advanceUntilIdle()

            assertEquals(1, store.installCount)
            assertNull(viewModel.state.value.failure)
        }

    @Test
    fun `cancelling an install reports cancellation and stops progress`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val store = RecordingStore(gate = gate)
            val viewModel = viewModel(store, supported = true, consented = true)

            viewModel.startDownload()
            assertTrue(viewModel.state.value.installing)
            viewModel.cancelInstall()

            assertFalse(viewModel.state.value.installing)
            assertEquals(LlmModelFailure.CANCELLED, viewModel.state.value.failure)
            gate.complete(Unit)
        }

    @Test
    fun `deleteAll removes the model from this device`() =
        runTest {
            val store = RecordingStore(installed = true)
            val viewModel = viewModel(store, supported = true, consented = true)
            assertTrue(viewModel.state.value.installed)

            store.installed = false
            viewModel.deleteAll()
            advanceUntilIdle()

            assertEquals(1, store.deleteAllCount)
            assertFalse(viewModel.state.value.installed)
        }

    @Test
    fun `verify removes a tampered model and reports the mismatch`() =
        runTest {
            val store = RecordingStore(installed = true, verifyResult = false)
            val viewModel = viewModel(store, supported = true, consented = true)

            store.installed = false
            viewModel.verifyInstalled()
            advanceUntilIdle()

            assertEquals(LlmModelFailure.CHECKSUM_MISMATCH, viewModel.state.value.failure)
            assertFalse(viewModel.state.value.installed)
        }

    @Test
    fun `progress is reported as a fraction of the catalog size`() =
        runTest {
            val store = RecordingStore(progressTo = model.sizeBytes / 2)
            val viewModel = viewModel(store, supported = true, consented = true)

            viewModel.startDownload()
            advanceUntilIdle()

            assertEquals(0f, viewModel.state.value.progressFraction, 0.001f)
        }

    private fun viewModel(
        store: LlmModelStore,
        supported: Boolean,
        consented: Boolean,
    ): LlmModelViewModel =
        LlmModelViewModel(
            modelStore = store,
            capabilityProvider = {
                if (supported) {
                    LlmCapability.Supported(model)
                } else {
                    LlmCapability.Unsupported(listOf(LlmUnsupportedReason.RUNTIME_UNAVAILABLE))
                }
            },
            consentRepository = FakeConsentRepository(consented),
            downloadSourceFactory = { LlmModelSource { ByteArrayInputStream(ByteArray(0)) } },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private class RecordingStore(
        private val result: LlmModelInstallResult =
            LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR),
        var installed: Boolean = false,
        private val verifyResult: Boolean = true,
        private val gate: CompletableDeferred<Unit>? = null,
        private val progressTo: Long = 0,
    ) : LlmModelStore {
        var installCount: Int = 0
            private set

        var deleteAllCount: Int = 0
            private set

        override fun state(definition: LlmModelDefinition): LlmModelState =
            if (installed) {
                LlmModelState.Installed(definition, definition.sizeBytes, 0)
            } else {
                LlmModelState.NotInstalled
            }

        override fun installedFile(definition: LlmModelDefinition): File? = if (installed) File("model.bin") else null

        override fun verifyInstalled(definition: LlmModelDefinition): Boolean = verifyResult

        override suspend fun install(
            definition: LlmModelDefinition,
            source: LlmModelSource,
            onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
        ): LlmModelInstallResult {
            installCount += 1
            if (progressTo > 0) onProgress(progressTo, definition.sizeBytes)
            gate?.await()
            if (result is LlmModelInstallResult.Installed) installed = true
            return result
        }

        override fun delete(definition: LlmModelDefinition): Boolean = true

        override fun deleteAll(): Boolean {
            deleteAllCount += 1
            installed = false
            return true
        }
    }

    private class FakeConsentRepository(
        granted: Boolean,
    ) : ConsentRepository {
        private val record =
            ConsentRecord(
                purpose = ConsentPurpose.MODEL_DOWNLOAD,
                consentedVersion = ConsentPurpose.MODEL_DOWNLOAD.policyVersion,
                grantedAtMillis = if (granted) 1L else null,
                revokedAtMillis = null,
            )

        private val consents = MutableStateFlow(mapOf(ConsentPurpose.MODEL_DOWNLOAD to record))

        override fun observeConsents(): Flow<Map<ConsentPurpose, ConsentRecord>> = consents

        override suspend fun isGranted(purpose: ConsentPurpose): Boolean = consents.value[purpose]?.granted == true

        override suspend fun grant(purpose: ConsentPurpose): ConsentRecord = record

        override suspend fun revoke(purpose: ConsentPurpose): ConsentRecord? = null
    }
}
