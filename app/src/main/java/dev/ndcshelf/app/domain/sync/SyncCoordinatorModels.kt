package dev.ndcshelf.app.domain.sync

/** 同期操作の失敗分類。UI表示とretry判断に使い、個人データを含めない。 */
enum class SyncFailureReason {
    /** ConsentPurpose.LIBRARY_SYNCの同意がない（fail-closed）。 */
    CONSENT_REQUIRED,
    NOT_ENABLED,
    ALREADY_ENABLED,
    LIBRARY_ALREADY_EXISTS,
    LIBRARY_NOT_FOUND,
    INVITE_INVALID,
    JOIN_NOT_READY,
    JOIN_REQUEST_NOT_FOUND,
    SECURITY_LOCKOUT,
    KEY_UNAVAILABLE,
    DEVICE_REVOKED,
    COUNTER_EXHAUSTED,
    INCOMPATIBLE_BACKEND,
    BACKEND,
    INTERNAL,
}

data class SyncFailure(
    val reason: SyncFailureReason,
    val backendKind: SyncBackendErrorKind? = null,
) {
    val retryable: Boolean
        get() = backendKind?.retryable == true
}

sealed interface SyncActionResult {
    data class Success(
        val appliedOperationCount: Int = 0,
    ) : SyncActionResult

    /** join要求を発行済みで、既存端末の承認待ち。 */
    data class JoinPending(
        val verificationCode: String,
    ) : SyncActionResult

    data class Failure(
        val failure: SyncFailure,
    ) : SyncActionResult
}

/** データ管理画面向けの同期構成状態。engine状態（SyncEngineStatus）を補完する。 */
data class SyncConfigurationStatus(
    val configured: Boolean = false,
    val activated: Boolean = false,
    val joinPending: Boolean = false,
    val backendType: String? = null,
    val deviceName: String? = null,
    val epoch: Int = 0,
    val hardwareBackedKeys: Boolean = false,
    val securityLockout: String? = null,
)
