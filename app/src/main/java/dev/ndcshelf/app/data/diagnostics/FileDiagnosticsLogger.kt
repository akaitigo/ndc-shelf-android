package dev.ndcshelf.app.data.diagnostics

import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import dev.ndcshelf.app.domain.diagnostics.DiagnosticEvent
import dev.ndcshelf.app.domain.diagnostics.DiagnosticsLogger
import java.io.File

/**
 * 端末内ファイルの循環バッファ。1行 = `timestampMillis<TAB>code` だけを保存し、
 * 件数と保持期間の上限を超えた記録を自動削除する。外部送信は行わない。
 */
class FileDiagnosticsLogger(
    directory: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxEvents: Int = MAX_EVENTS,
    private val retentionMillis: Long = RETENTION_MILLIS,
) : DiagnosticsLogger {
    private val file = File(directory, FILE_NAME)
    private val lock = Any()

    override fun log(code: DiagnosticCode) {
        runCatching {
            synchronized(lock) {
                val events = readEvents() + DiagnosticEvent(nowMillis(), code)
                writeEvents(prune(events))
            }
        }
    }

    override fun recentEvents(): List<DiagnosticEvent> =
        runCatching {
            synchronized(lock) { prune(readEvents()) }
        }.getOrDefault(emptyList())

    override fun clearAll() {
        runCatching {
            synchronized(lock) { file.delete() }
        }
    }

    private fun prune(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val cutoff = nowMillis() - retentionMillis
        return events.filter { it.timestampMillis >= cutoff }.takeLast(maxEvents)
    }

    private fun readEvents(): List<DiagnosticEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2) return@mapNotNull null
            val timestamp = parts[0].toLongOrNull() ?: return@mapNotNull null
            val code =
                DiagnosticCode.entries.firstOrNull { it.name == parts[1] }
                    ?: return@mapNotNull null
            DiagnosticEvent(timestamp, code)
        }
    }

    private fun writeEvents(events: List<DiagnosticEvent>) {
        file.parentFile?.mkdirs()
        file.writeText(
            events.joinToString(separator = "\n") { event ->
                "${event.timestampMillis}\t${event.code.name}"
            },
        )
    }

    private companion object {
        const val FILE_NAME = "diagnostics-events.log"
        const val MAX_EVENTS = 200
        const val RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1_000
    }
}
