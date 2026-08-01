package dev.ndcshelf.app.domain.ai.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelUrlPolicyTest {
    @Test
    fun `https urls on allowed hosts are accepted`() {
        assertTrue(LlmModelUrlPolicy.isAllowed("https://huggingface.co/org/model/resolve/main/model.bin"))
        assertTrue(LlmModelUrlPolicy.isAllowed("https://huggingface.co:443/org/model/resolve/main/model.bin"))
    }

    @Test
    fun `plaintext and non allowed hosts are rejected`() {
        listOf(
            "http://huggingface.co/org/model/model.bin",
            "https://evil.example.com/model.bin",
            "https://huggingface.co.evil.example.com/model.bin",
            "https://evil.example.com/huggingface.co/model.bin",
            "file:///data/local/tmp/model.bin",
            "content://com.example/model.bin",
        ).forEach { url ->
            assertFalse(url, LlmModelUrlPolicy.isAllowed(url))
        }
    }

    @Test
    fun `userinfo alternate ports fragments and traversal are rejected`() {
        listOf(
            "https://user:pass@huggingface.co/org/model/model.bin",
            "https://huggingface.co:8443/org/model/model.bin",
            "https://huggingface.co/org/model/model.bin#fragment",
            "https://huggingface.co/../../etc/passwd",
            "https://huggingface.co",
        ).forEach { url ->
            assertFalse(url, LlmModelUrlPolicy.isAllowed(url))
        }
    }

    @Test
    fun `catalog definitions reject urls outside the policy`() {
        assertThrows(IllegalArgumentException::class.java) {
            testModelDefinition(downloadUrl = "http://huggingface.co/org/model/model.bin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            testModelDefinition(downloadUrl = "https://cdn.example.com/model.bin")
        }
    }

    @Test
    fun `catalog definitions reject malformed checksums and sizes`() {
        assertThrows(IllegalArgumentException::class.java) { testModelDefinition(sha256 = "abc") }
        assertThrows(IllegalArgumentException::class.java) { testModelDefinition(sha256 = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { testModelDefinition(sizeBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            testModelDefinition(sizeBytes = LlmModelDefinition.MAX_MODEL_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            testModelDefinition(sizeBytes = 100, requiredFreeBytes = 10)
        }
        assertThrows(IllegalArgumentException::class.java) { testModelDefinition(requiredAbis = emptySet()) }
    }

    @Test
    fun `allowed hosts stay minimal and https only`() {
        assertTrue(LlmModelUrlPolicy.ALLOWED_HOSTS.isNotEmpty())
        LlmModelUrlPolicy.ALLOWED_HOSTS.forEach { host ->
            assertTrue(host, host == host.lowercase())
            assertFalse(host, "/" in host)
        }
    }
}
