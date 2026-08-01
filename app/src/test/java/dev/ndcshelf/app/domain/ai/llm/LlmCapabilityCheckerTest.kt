package dev.ndcshelf.app.domain.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCapabilityCheckerTest {
    @Test
    fun `supported when every requirement is met`() {
        val model = testModelDefinition()
        val capability = LlmCapabilityChecker.evaluate(supportedProfile(), model, enabled = true)

        assertTrue(capability is LlmCapability.Supported)
        assertTrue(LlmCapabilityChecker.canAcquire(supportedProfile(), model, enabled = true))
    }

    @Test
    fun `disabled feature flag blocks the llm path`() {
        val capability = LlmCapabilityChecker.evaluate(supportedProfile(), testModelDefinition(), enabled = false)

        assertTrue(capability is LlmCapability.Unsupported)
        assertEquals(
            LlmUnsupportedReason.DISABLED,
            (capability as LlmCapability.Unsupported).primaryReason,
        )
    }

    @Test
    fun `missing model definition is unsupported`() {
        val capability = LlmCapabilityChecker.evaluate(supportedProfile(), model = null, enabled = true)

        val reasons = (capability as LlmCapability.Unsupported).reasons
        assertTrue(LlmUnsupportedReason.NO_MODEL_AVAILABLE in reasons)
    }

    @Test
    fun `api 23 device is rejected instead of raising minSdk`() {
        val capability =
            LlmCapabilityChecker.evaluate(
                supportedProfile(sdkInt = 23),
                testModelDefinition(minSdkInt = 24),
                enabled = true,
            )

        assertTrue(LlmUnsupportedReason.SDK_TOO_OLD in (capability as LlmCapability.Unsupported).reasons)
    }

    @Test
    fun `unsupported abi is rejected`() {
        val capability =
            LlmCapabilityChecker.evaluate(
                supportedProfile(supportedAbis = listOf("armeabi-v7a")),
                testModelDefinition(requiredAbis = setOf("arm64-v8a")),
                enabled = true,
            )

        assertTrue(LlmUnsupportedReason.ABI_UNSUPPORTED in (capability as LlmCapability.Unsupported).reasons)
    }

    @Test
    fun `insufficient ram and storage are both reported`() {
        val capability =
            LlmCapabilityChecker.evaluate(
                supportedProfile(totalRamBytes = 1L, availableStorageBytes = 1L),
                testModelDefinition(),
                enabled = true,
            )

        val reasons = (capability as LlmCapability.Unsupported).reasons
        assertTrue(LlmUnsupportedReason.INSUFFICIENT_RAM in reasons)
        assertTrue(LlmUnsupportedReason.INSUFFICIENT_STORAGE in reasons)
    }

    @Test
    fun `low ram device is rejected even when other requirements pass`() {
        val capability =
            LlmCapabilityChecker.evaluate(
                supportedProfile(isLowRamDevice = true),
                testModelDefinition(),
                enabled = true,
            )

        assertTrue(LlmUnsupportedReason.LOW_RAM_DEVICE in (capability as LlmCapability.Unsupported).reasons)
    }

    @Test
    fun `missing native runtime is rejected`() {
        val capability =
            LlmCapabilityChecker.evaluate(
                supportedProfile(runtimeAvailable = false),
                testModelDefinition(),
                enabled = true,
            )

        assertTrue(LlmUnsupportedReason.RUNTIME_UNAVAILABLE in (capability as LlmCapability.Unsupported).reasons)
    }

    @Test
    fun `acquisition is blocked on devices that could not run the model`() {
        assertFalse(
            LlmCapabilityChecker.canAcquire(
                supportedProfile(sdkInt = 23),
                testModelDefinition(minSdkInt = 24),
                enabled = true,
            ),
        )
    }

    @Test
    fun `catalog is fail closed until a model is approved`() {
        assertEquals(emptyList<LlmModelDefinition>(), LlmModelCatalog.models)
        assertEquals(null, LlmModelCatalog.defaultModel)
        assertEquals(null, LlmModelCatalog.findById("test-model"))
    }
}
