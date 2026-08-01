package dev.ndcshelf.app.data.diagnostics

import dev.ndcshelf.app.domain.ai.llm.LlmFailureKind
import dev.ndcshelf.app.domain.ai.llm.LlmInferenceTelemetry
import dev.ndcshelf.app.domain.ai.llm.LlmRuntimeId
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCategory
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticEvent
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLlmTelemetrySinkTest {
    private val logged = mutableListOf<DiagnosticCode>()

    private val logger =
        object : DiagnosticsLogger {
            override fun log(code: DiagnosticCode) {
                logged += code
            }

            override fun recentEvents(): List<DiagnosticEvent> = emptyList()

            override fun clearAll() {
                logged.clear()
            }
        }

    @Test
    fun `successful inference records no diagnostic code`() {
        val sink = DiagnosticsLlmTelemetrySink(logger)

        sink.record(telemetry(failure = null))

        assertTrue(logged.isEmpty())
        assertEquals(1, sink.recent().size)
    }

    @Test
    fun `every failure kind maps to an allowlisted on device llm code`() {
        val sink = DiagnosticsLlmTelemetrySink(logger)

        LlmFailureKind.entries.forEach { kind -> sink.record(telemetry(failure = kind)) }

        assertEquals(LlmFailureKind.entries.size, logged.size)
        logged.forEach { code -> assertEquals(DiagnosticCategory.ON_DEVICE_LLM, code.category) }
    }

    @Test
    fun `telemetry carries only catalog identifiers and numbers`() {
        val sink = DiagnosticsLlmTelemetrySink(logger)

        sink.record(telemetry(failure = null))

        val record = sink.recent().single()
        assertEquals("test-model", record.modelId)
        assertEquals(16, record.modelHashPrefix.length)
        assertEquals(LlmRuntimeId.FAKE, record.runtime)
        assertTrue(record.inferenceMillis >= 0)
    }

    @Test
    fun `clearAll removes every buffered record`() {
        val sink = DiagnosticsLlmTelemetrySink(logger)
        sink.record(telemetry(failure = LlmFailureKind.INVALID_OUTPUT))

        sink.clearAll()

        assertTrue(sink.recent().isEmpty())
    }

    private fun telemetry(failure: LlmFailureKind?) =
        LlmInferenceTelemetry(
            modelId = "test-model",
            modelVersion = "1.0.0",
            modelHashPrefix = "0".repeat(16),
            runtime = LlmRuntimeId.FAKE,
            runtimeVersion = "fake-1",
            initializationMillis = 12,
            inferenceMillis = 34,
            promptChars = 100,
            outputChars = 50,
            failure = failure,
        )
}
