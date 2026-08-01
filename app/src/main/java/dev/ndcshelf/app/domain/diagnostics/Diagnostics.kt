package dev.ndcshelf.app.domain.diagnostics

/**
 * 端末内診断の記録。個人データ（ISBN・タイトル・著者・場所・検索文・URI・
 * token）は構造上持てないよう、事前定義されたcodeだけをallowlist方式で記録する。
 * 自由形式の文字列は受け付けない。
 */
enum class DiagnosticCategory {
    NETWORK,
    DATABASE,
    MIGRATION,
    BACKUP,
    IMPORT_EXPORT,
    SCAN,
    SYNC,
    DOCUMENT_PROVIDER,

    /** 端末内LLM（AI司書）。質問文・書誌・回答は記録しない。 */
    ON_DEVICE_LLM,
}

/** 記録できる事象の全列挙。ここに無い事象は記録できない（fail-closed）。 */
enum class DiagnosticCode(
    val category: DiagnosticCategory,
) {
    NDL_OFFLINE(DiagnosticCategory.NETWORK),
    NDL_TIMEOUT(DiagnosticCategory.NETWORK),
    NDL_RATE_LIMITED(DiagnosticCategory.NETWORK),
    NDL_SERVER_ERROR(DiagnosticCategory.NETWORK),
    NDL_PARSE_ERROR(DiagnosticCategory.NETWORK),
    DB_OPEN_ERROR(DiagnosticCategory.DATABASE),
    DB_CONSTRAINT_ERROR(DiagnosticCategory.DATABASE),
    MIGRATION_COMPLETED(DiagnosticCategory.MIGRATION),
    BACKUP_CREATE_FAILED(DiagnosticCategory.BACKUP),
    BACKUP_RESTORE_REJECTED(DiagnosticCategory.BACKUP),
    BACKUP_RESTORE_FAILED(DiagnosticCategory.BACKUP),
    IMPORT_VALIDATION_FAILED(DiagnosticCategory.IMPORT_EXPORT),
    EXPORT_FAILED(DiagnosticCategory.IMPORT_EXPORT),
    SCAN_CAMERA_ERROR(DiagnosticCategory.SCAN),
    SYNC_UPLOAD_FAILED(DiagnosticCategory.SYNC),
    SYNC_CONFLICT_RECORDED(DiagnosticCategory.SYNC),
    SAF_OPEN_FAILED(DiagnosticCategory.DOCUMENT_PROVIDER),
    SAF_WRITE_FAILED(DiagnosticCategory.DOCUMENT_PROVIDER),
    LLM_DEVICE_UNSUPPORTED(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_MODEL_MISSING(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_MODEL_CHECKSUM_MISMATCH(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_MODEL_DOWNLOAD_FAILED(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_MODEL_INSTALLED(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_INITIALIZATION_FAILED(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_INFERENCE_FAILED(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_INVALID_OUTPUT(DiagnosticCategory.ON_DEVICE_LLM),
    LLM_DEGRADED_TO_HEURISTIC(DiagnosticCategory.ON_DEVICE_LLM),
}

data class DiagnosticEvent(
    val timestampMillis: Long,
    val code: DiagnosticCode,
)

interface DiagnosticsLogger {
    /** allowlistにあるcodeだけを記録する。呼び出しは失敗してもアプリ動作へ影響しない。 */
    fun log(code: DiagnosticCode)

    fun recentEvents(): List<DiagnosticEvent>

    fun clearAll()
}

/** 何も記録しない既定実装。テストや診断無効環境で使う。 */
object NoOpDiagnosticsLogger : DiagnosticsLogger {
    override fun log(code: DiagnosticCode) = Unit

    override fun recentEvents(): List<DiagnosticEvent> = emptyList()

    override fun clearAll() = Unit
}

/**
 * 診断画面と共有ファイルへ載せられる非機密状態のsnapshot。
 * フィールドは数値・enum・boolean・バージョン文字列だけで構成し、
 * 自由形式の個人文字列を含めない。
 */
data class DiagnosticsSnapshot(
    val appVersionName: String,
    val appVersionCode: Int,
    val androidSdkInt: Int,
    val databaseVersion: Int,
    val workCount: Int,
    val editionCount: Int,
    val copyCount: Int,
    val seriesCount: Int,
    val scanSessionCount: Int,
    val syncEnabled: Boolean,
    val syncPendingOperations: Int,
    val syncUnresolvedConflicts: Int,
    val syncLastSuccessAtMillis: Long?,
    val consentedPurposes: List<String>,
    val recentEvents: List<DiagnosticEvent>,
)
