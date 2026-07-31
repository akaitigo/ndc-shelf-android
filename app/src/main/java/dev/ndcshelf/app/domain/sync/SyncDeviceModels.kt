package dev.ndcshelf.app.domain.sync

/** protocol v1で唯一有効なalgorithm suite ID。意味を変更してはならない。 */
const val SYNC_SUITE_ID = "P256_HKDF_SHA256_AES256GCM_ECDSA_P256_SHA256"

/** protocol version。majorが異なるobjectは全体を拒否する。 */
const val SYNC_PROTOCOL_VERSION = "1.0"

const val SYNC_PROTOCOL_MAJOR = 1

/** 復号後canonical JSONの上限（8 MiB、padding前）。 */
const val MAX_SYNC_OBJECT_PLAINTEXT_BYTES = 8 * 1024 * 1024

/** 署名済みregistryに載る端末。公開鍵はbase64url unpadded。 */
data class SyncRegistryDevice(
    val deviceId: String,
    val name: String,
    /** X.509 SubjectPublicKeyInfo DER（base64url unpadded）。 */
    val signingPublicKey: String,
    /** RFC 9180 SerializePublicKeyの65-byte uncompressed SEC1（base64url unpadded）。 */
    val hpkePublicKey: String,
    val addedAtGeneration: Int,
    val revokedAtGeneration: Int?,
) {
    val revoked: Boolean
        get() = revokedAtGeneration != null
}

/** 署名済みdevice registry文書のdomain表現。 */
data class SyncDeviceRegistry(
    val libraryOpaqueId: String,
    val registryGeneration: Int,
    val epoch: Int,
    val devices: List<SyncRegistryDevice>,
) {
    init {
        require(registryGeneration >= 1 && epoch >= 1)
        require(devices.isNotEmpty() && devices.size <= MAX_SYNC_DEVICES)
    }

    fun device(deviceId: String): SyncRegistryDevice? = devices.firstOrNull { it.deviceId == deviceId }

    fun activeDevices(): List<SyncRegistryDevice> = devices.filterNot(SyncRegistryDevice::revoked)
}

/** head文書のdomain表現。backendはこの内容を解釈しない。 */
data class SyncLibraryHead(
    val libraryOpaqueId: String,
    val generation: Long,
    val epoch: Int,
    val registryGeneration: Int,
    val registryHash: String,
    val deviceLogHeads: Map<String, String>,
    val snapshotObjectId: String?,
) {
    init {
        require(generation >= 1 && epoch >= 1 && registryGeneration >= 1)
    }
}

/** 端末一覧UI向けの表示model。個人データは端末名だけ。 */
data class SyncDeviceInfo(
    val deviceId: String,
    val name: String,
    val isSelf: Boolean,
    val revoked: Boolean,
    val lastSyncAtMillis: Long?,
    val addedAtGeneration: Int,
)

/** 招待コード（QR相当のout-of-band一回限りsecret）。 */
data class SyncInvite(
    val nonce: String,
    val secret: String,
    val expiresAtMillis: Long,
) {
    /** 既存端末に表示し、新端末が入力するコード。 */
    fun encode(): String = "$nonce.$secret"

    companion object {
        const val VALIDITY_MILLIS = 10L * 60 * 1_000

        fun decode(code: String): Pair<String, String>? {
            val parts = code.trim().split(".")
            if (parts.size != 2) return null
            val (nonce, secret) = parts
            if (nonce.isBlank() || secret.isBlank()) return null
            if (nonce.length > 64 || secret.length > 128) return null
            return nonce to secret
        }
    }
}

/** 未承認の参加リクエスト。承認前に双方でverificationCodeを照合する。 */
data class SyncJoinCandidate(
    val deviceId: String,
    val deviceName: String,
    val signingPublicKey: String,
    val hpkePublicKey: String,
    val inviteNonce: String,
    val verificationCode: String,
)
