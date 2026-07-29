package dev.ndcshelf.app.data.diagnostics

import dev.ndcshelf.app.data.remote.BookMetadataFailure
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger

/**
 * NDL書誌取得の失敗種別だけを診断へ記録するdecorator。ISBN・書誌内容・URLは
 * 記録しない（DiagnosticCodeのallowlistに載らないため構造的に不可能）。
 */
class DiagnosticsLoggingBookMetadataService(
    private val delegate: BookMetadataService,
    private val logger: DiagnosticsLogger,
) : BookMetadataService {
    override suspend fun findByIsbn(isbn13: String): BookMetadataLookupResult {
        val result = delegate.findByIsbn(isbn13)
        if (result is BookMetadataLookupResult.Failure) {
            logger.log(result.reason.toDiagnosticCode())
        }
        return result
    }
}

private fun BookMetadataFailure.toDiagnosticCode(): DiagnosticCode =
    when (this) {
        BookMetadataFailure.OFFLINE -> DiagnosticCode.NDL_OFFLINE
        BookMetadataFailure.TIMEOUT -> DiagnosticCode.NDL_TIMEOUT
        BookMetadataFailure.RATE_LIMITED -> DiagnosticCode.NDL_RATE_LIMITED
        BookMetadataFailure.SERVER -> DiagnosticCode.NDL_SERVER_ERROR
        BookMetadataFailure.NETWORK -> DiagnosticCode.NDL_OFFLINE
        BookMetadataFailure.CLIENT -> DiagnosticCode.NDL_SERVER_ERROR
        BookMetadataFailure.PARSE -> DiagnosticCode.NDL_PARSE_ERROR
    }
