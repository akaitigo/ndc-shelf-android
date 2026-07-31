package dev.ndcshelf.app.domain.sync

/**
 * transport共通のエラー分類。SYNC_PROTOCOL.md 10節の失敗分類に対応し、
 * HTTPS adapterとSAFフォルダadapterの双方で同じenumを使う。
 * メッセージへ個人データ・鍵材料・平文payloadを含めてはならない。
 */
enum class SyncBackendErrorKind(
    /** 上限付きexponential backoffでの自動retryを許可するか。 */
    val retryable: Boolean,
) {
    /** 到達不能・timeout等の一時的なnetwork障害。 */
    NETWORK(retryable = true),

    /** TLS handshake・証明書検証の失敗。証明書検証は無効化しない。 */
    TLS_FAILURE(retryable = false),

    /** 認証情報の不備。自動retryせず再認証を要求する。 */
    AUTHENTICATION_FAILED(retryable = false),

    /** 認証tokenの期限切れ。再認証を要求する。 */
    TOKEN_EXPIRED(retryable = false),

    /** 429等のrate limit。backoff後に再試行できる。 */
    RATE_LIMITED(retryable = true),

    /** 5xx・メンテナンス等のサービス停止。 */
    SERVICE_UNAVAILABLE(retryable = true),

    /** SAF: フォルダへのアクセス権限喪失。利用者の再選択が必要。 */
    PERMISSION_LOST(retryable = false),

    /** SAF: 保存先の容量不足。 */
    STORAGE_FULL(retryable = false),

    /** 分類できないIO失敗。 */
    IO_FAILURE(retryable = true),

    /** 参照したobject・headが存在しない。 */
    NOT_FOUND(retryable = false),

    /** head compare-and-set競合。pull後にmergeして再試行する。 */
    CAS_CONFLICT(retryable = true),

    /** capability・protocol versionの不一致。uploadを開始しない。 */
    INCOMPATIBLE_CAPABILITY(retryable = false),

    /** backendの応答がprotocolに違反している。 */
    INVALID_RESPONSE(retryable = false),
}

class SyncBackendException(
    val kind: SyncBackendErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * SYNC_PROTOCOL.md 9節のcapability宣言。必須capability不一致では
 * uploadを開始してはならない。
 */
data class SyncBackendCapabilities(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val suites: Set<String>,
    val maxObjectSizeBytes: Long,
    val supportsCompareAndSet: Boolean,
    val retentionDays: Int?,
    val deletionSlaDays: Int,
    val rateLimitPerMinute: Int?,
    val exportAvailable: Boolean,
)

/** headの生bytesと楽観ロック用etag（内容hash）。 */
data class SyncHeadRecord(
    val bytes: ByteArray,
    val etag: String,
) {
    override fun equals(other: Any?): Boolean = other is SyncHeadRecord && etag == other.etag && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = etag.hashCode()
}

sealed interface SyncCasResult {
    data class Committed(
        val newEtag: String,
    ) : SyncCasResult

    data class Conflict(
        val current: SyncHeadRecord?,
    ) : SyncCasResult
}

/** backendへ保存済みのHPKE device key envelope（不透明bytes）。 */
data class StoredDeviceEnvelope(
    val envelopeId: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is StoredDeviceEnvelope && envelopeId == other.envelopeId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = envelopeId.hashCode()
}

/** backendへ保存済みの参加リクエスト（不透明bytes）。 */
data class StoredJoinRequest(
    val requestId: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is StoredJoinRequest && requestId == other.requestId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = requestId.hashCode()
}

/** remote全削除の完了確認。SAF adapterでは削除後の空確認を意味する。 */
data class SyncDeletionReceipt(
    val requestedAtMillis: Long,
    val completedAtMillis: Long?,
    val remainingObjectCount: Int,
    val physicalDeletionNote: String,
)

/**
 * 交換可能な同期backendのtransport契約（SYNC_PROTOCOL.md 9節）。
 * backendはE2EE ciphertextとheadだけを保存し、domain payloadを解釈しない。
 * 実装はSyncBackendExceptionで失敗を分類する。
 */
interface SyncBackend {
    suspend fun getCapabilities(): SyncBackendCapabilities

    /** libraryのnamespace・初期registry・初期headを作成する。既存libraryには失敗する。 */
    suspend fun createLibrary(
        initialRegistry: ByteArray,
        initialHead: ByteArray,
    )

    /** libraryが既に存在するか。SAFでは選択フォルダ内のlibrary directoryの有無。 */
    suspend fun libraryExists(): Boolean

    suspend fun getHead(): SyncHeadRecord?

    suspend fun compareAndSetHead(
        expectedEtag: String?,
        newHead: ByteArray,
    ): SyncCasResult

    /** content-addressed・immutable。既存objectIdへ異なるbytesを保存してはならない。 */
    suspend fun putObjectIfAbsent(
        objectId: String,
        bytes: ByteArray,
    )

    suspend fun getObject(objectId: String): ByteArray

    /** 署名済みdevice registry文書をgenerationごとにimmutableへ保存・取得する。 */
    suspend fun putRegistryIfAbsent(
        generation: Int,
        bytes: ByteArray,
    )

    suspend fun getRegistry(generation: Int): ByteArray

    suspend fun listDeviceEnvelopes(): List<StoredDeviceEnvelope>

    suspend fun putDeviceEnvelopeIfAuthorized(
        envelopeId: String,
        bytes: ByteArray,
    )

    suspend fun listJoinRequests(): List<StoredJoinRequest>

    suspend fun putJoinRequest(
        requestId: String,
        bytes: ByteArray,
    )

    suspend fun deleteJoinRequest(requestId: String)

    /** 全opaque object・head・wrapped key・registryを削除し、完了確認を返す。 */
    suspend fun requestRemoteDeletion(): SyncDeletionReceipt

    suspend fun getDeletionReceipt(): SyncDeletionReceipt?
}
