package dev.ndcshelf.app.data.diagnostics

import dev.ndcshelf.app.domain.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDiagnosticsLoggerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_700_000_000_000L

    private fun logger(maxEvents: Int = 5) =
        FileDiagnosticsLogger(
            directory = folder.root,
            nowMillis = { now },
            maxEvents = maxEvents,
        )

    @Test
    fun logsAreStructuredCodesOnlyAndRoundTrip() {
        val logger = logger()

        logger.log(DiagnosticCode.NDL_TIMEOUT)
        now += 1_000
        logger.log(DiagnosticCode.BACKUP_RESTORE_REJECTED)

        val events = logger.recentEvents()
        assertEquals(
            listOf(DiagnosticCode.NDL_TIMEOUT, DiagnosticCode.BACKUP_RESTORE_REJECTED),
            events.map { it.code },
        )
        assertEquals(1_700_000_000_000L, events.first().timestampMillis)
    }

    @Test
    fun circularBufferKeepsOnlyNewestEvents() {
        val logger = logger(maxEvents = 3)

        repeat(5) {
            logger.log(DiagnosticCode.NDL_OFFLINE)
            now += 1
        }
        logger.log(DiagnosticCode.SCAN_CAMERA_ERROR)

        val events = logger.recentEvents()
        assertEquals(3, events.size)
        assertEquals(DiagnosticCode.SCAN_CAMERA_ERROR, events.last().code)
    }

    @Test
    fun retentionDropsEventsOlderThanFourteenDays() {
        val logger = logger()
        logger.log(DiagnosticCode.NDL_OFFLINE)

        now += 15L * 24 * 60 * 60 * 1_000
        logger.log(DiagnosticCode.NDL_TIMEOUT)

        assertEquals(listOf(DiagnosticCode.NDL_TIMEOUT), logger.recentEvents().map { it.code })
    }

    @Test
    fun clearAllRemovesEveryEvent() {
        val logger = logger()
        logger.log(DiagnosticCode.NDL_OFFLINE)

        logger.clearAll()

        assertTrue(logger.recentEvents().isEmpty())
    }

    @Test
    fun corruptOrUnknownLinesAreIgnoredInsteadOfCrashing() {
        val logger = logger()
        logger.log(DiagnosticCode.NDL_OFFLINE)
        folder.root.resolve("diagnostics-events.log").appendText(
            "\nnot-a-number\tNDL_OFFLINE\n123\tUNKNOWN_CODE\ngarbage",
        )

        assertEquals(listOf(DiagnosticCode.NDL_OFFLINE), logger.recentEvents().map { it.code })
    }
}
