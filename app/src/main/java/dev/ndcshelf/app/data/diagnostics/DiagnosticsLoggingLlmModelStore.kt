package dev.ndcshelf.app.data.diagnostics

import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallFailure
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallResult
import dev.ndcshelf.app.domain.ai.llm.LlmModelSource
import dev.ndcshelf.app.domain.ai.llm.LlmModelState
import dev.ndcshelf.app.domain.ai.llm.LlmModelStore
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger
import java.io.File

/**
 * モデル導入・整合性確認の結果を端末内診断へ記録するdecorator。
 *
 * 記録するのはallowlistの[DiagnosticCode]だけで、モデルID・URL・ファイル名・
 * 蔵書データは一切書かない（[DiagnosticsLoggingBookMetadataService]と同じ方式）。
 */
class DiagnosticsLoggingLlmModelStore(
    private val delegate: LlmModelStore,
    private val logger: DiagnosticsLogger,
) : LlmModelStore {
    override fun state(definition: LlmModelDefinition): LlmModelState = delegate.state(definition)

    override fun installedFile(definition: LlmModelDefinition): File? = delegate.installedFile(definition)

    override fun verifyInstalled(definition: LlmModelDefinition): Boolean {
        val verified = delegate.verifyInstalled(definition)
        if (!verified) logger.log(DiagnosticCode.LLM_MODEL_CHECKSUM_MISMATCH)
        return verified
    }

    override suspend fun install(
        definition: LlmModelDefinition,
        source: LlmModelSource,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): LlmModelInstallResult {
        val result = delegate.install(definition, source, onProgress)
        when (result) {
            is LlmModelInstallResult.Installed -> logger.log(DiagnosticCode.LLM_MODEL_INSTALLED)
            is LlmModelInstallResult.Failed -> logger.log(result.reason.toDiagnosticCode())
        }
        return result
    }

    override fun delete(definition: LlmModelDefinition): Boolean = delegate.delete(definition)

    override fun deleteAll(): Boolean = delegate.deleteAll()

    private fun LlmModelInstallFailure.toDiagnosticCode(): DiagnosticCode =
        when (this) {
            LlmModelInstallFailure.CHECKSUM_MISMATCH -> DiagnosticCode.LLM_MODEL_CHECKSUM_MISMATCH

            LlmModelInstallFailure.TRANSPORT,
            LlmModelInstallFailure.SIZE_MISMATCH,
            LlmModelInstallFailure.STORAGE_ERROR,
            -> DiagnosticCode.LLM_MODEL_DOWNLOAD_FAILED
        }
}
