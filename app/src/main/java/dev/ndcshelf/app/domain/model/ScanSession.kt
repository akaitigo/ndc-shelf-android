package dev.ndcshelf.app.domain.model

data class ScanSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val attempts: List<ScanAttempt>,
) {
    val isActive: Boolean get() = endedAt == null
    val addedCount: Int get() = attempts.count { it.outcome == ScanAttemptOutcome.ADDED }
    val activeAddedCount: Int get() = attempts.count {
        it.outcome == ScanAttemptOutcome.ADDED && it.undoneAt == null
    }
}

data class ScanAttempt(
    val id: String,
    val sessionId: String,
    val isbn: String,
    val outcome: ScanAttemptOutcome,
    val copyId: String?,
    val attemptedAt: Long,
    val undoneAt: Long?,
)

enum class ScanAttemptOutcome {
    ADDED,
    DUPLICATE,
    INVALID,
    NOT_FOUND,
    FAILURE,
}
