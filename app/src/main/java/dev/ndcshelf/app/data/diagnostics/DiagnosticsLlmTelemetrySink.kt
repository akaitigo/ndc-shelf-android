package dev.ndcshelf.app.data.diagnostics

import dev.ndcshelf.app.domain.ai.llm.InMemoryLlmTelemetrySink
import dev.ndcshelf.app.domain.ai.llm.LlmFailureKind
import dev.ndcshelf.app.domain.ai.llm.LlmInferenceTelemetry
import dev.ndcshelf.app.domain.ai.llm.LlmTelemetrySink
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger

/**
 * 推論テレメトリを端末内診断へ橋渡しするsink。
 *
 * 端末内診断ログへはallowlistの[DiagnosticCode]だけを書き、model version・
 * runtime・hash先頭・所要時間は揮発する端末内バッファ（[InMemoryLlmTelemetrySink]）
 * にのみ保持する。質問文・書誌・回答は構造上どちらにも入らない。
 */
class DiagnosticsLlmTelemetrySink(
    private val logger: DiagnosticsLogger,
    private val delegate: LlmTelemetrySink = InMemoryLlmTelemetrySink(),
) : LlmTelemetrySink {
    override fun record(telemetry: LlmInferenceTelemetry) {
        delegate.record(telemetry)
        telemetry.failure?.let { failure -> logger.log(failure.toDiagnosticCode()) }
    }

    override fun recent(): List<LlmInferenceTelemetry> = delegate.recent()

    override fun clearAll() {
        delegate.clearAll()
    }

    private fun LlmFailureKind.toDiagnosticCode(): DiagnosticCode =
        when (this) {
            LlmFailureKind.DEVICE_UNSUPPORTED -> DiagnosticCode.LLM_DEVICE_UNSUPPORTED
            LlmFailureKind.MODEL_MISSING -> DiagnosticCode.LLM_MODEL_MISSING
            LlmFailureKind.MODEL_CORRUPTED -> DiagnosticCode.LLM_MODEL_CHECKSUM_MISMATCH
            LlmFailureKind.INITIALIZATION_FAILED -> DiagnosticCode.LLM_INITIALIZATION_FAILED
            LlmFailureKind.INFERENCE_FAILED -> DiagnosticCode.LLM_INFERENCE_FAILED
            LlmFailureKind.INVALID_OUTPUT -> DiagnosticCode.LLM_INVALID_OUTPUT
        }
}
