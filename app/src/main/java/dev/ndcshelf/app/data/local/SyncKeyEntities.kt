package dev.ndcshelf.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 同期library・端末identityのsingleton状態。鍵の平文は含めない。
 * encryptionCounterはobject作成前にtransactionalへ増加し、再利用しない。
 */
@Entity(tableName = "sync_identity")
data class SyncIdentityEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** 128-bit libraryId（base64url unpadded）。subkey導出のsalt。 */
    val libraryId: String,
    /** SHA-256(libraryId || backendSalt)のopaque表現。 */
    val libraryOpaqueId: String,
    /** 128-bit device ID（base64url unpadded）。sync_settings.deviceIdと一致。 */
    val deviceId: String,
    val deviceName: String,
    val epoch: Int,
    val registryGeneration: Int,
    /** 検証済みcurrent registryのcanonical hash（base64url unpadded）。 */
    val registryHash: String?,
    /** 最後に検証したheadのlibrary generation。rollback検出に使う。 */
    val headGeneration: Long,
    val trustedHeadHash: String?,
    val encryptionCounter: Long,
    /** 自deviceのlog headのobjectId（hash chain継続用）。 */
    val lastUploadedObjectId: String?,
    val backendType: String,
    /** SAFフォルダadapterでは選択tree URI。認証tokenを含めない。 */
    val backendConfig: String,
    val hardwareBackedKeys: Boolean,
    /** initial snapshot uploadが成功して同期ONが確定したか。 */
    val activated: Boolean,
    /** security quarantineで同期停止中の理由。個人データを含めない。 */
    val securityLockout: String?,
    val createdAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val BACKEND_SAF_FOLDER = "saf-folder"
    }
}

/** Keystore wrapping keyで暗号化したHPKE秘密鍵・epoch key。 */
@Entity(tableName = "sync_wrapped_keys", primaryKeys = ["keyType", "keyVersion"])
data class SyncWrappedKeyEntity(
    val keyType: String,
    /** epoch keyはepoch番号、HPKE秘密鍵は1。 */
    val keyVersion: Int,
    val nonce: String,
    val ciphertext: String,
    val keyAliasVersion: Int,
    val createdAt: Long,
)

/** 検証済みregistryのlocal cacheと端末一覧表示用の状態。 */
@Entity(
    tableName = "sync_peer_devices",
    indices = [Index(value = ["isSelf"])],
)
data class SyncPeerDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val signingPublicKey: String,
    val hpkePublicKey: String,
    val addedAtGeneration: Int,
    val revokedAtGeneration: Int?,
    val lastSyncAt: Long?,
    /** そのdeviceのlogで最後に処理したobjectId（hash chain検証用）。 */
    val lastObjectId: String?,
    /** そのdeviceのlogで最後に観測したencryption counter（nonce再利用検出）。 */
    val lastEncryptionCounter: Long,
    val isSelf: Boolean,
)

/** 端末追加用の短時間・一回限りの招待。consume後は再利用しない。 */
@Entity(tableName = "sync_invites")
data class SyncInviteEntity(
    @PrimaryKey val nonce: String,
    val secret: String,
    val createdAt: Long,
    val expiresAt: Long,
    val consumedAt: Long?,
)

/** 処理済みdevice key envelopeとnonceの一回性記録。 */
@Entity(tableName = "sync_processed_envelopes")
data class SyncProcessedEnvelopeEntity(
    @PrimaryKey val envelopeId: String,
    val inviteNonce: String,
    val processedAt: Long,
)

/** security-invalid objectのsize制限付きquarantine。復号せず保存する。 */
@Entity(tableName = "sync_quarantine")
data class SyncQuarantineEntity(
    @PrimaryKey val objectId: String,
    val reason: String,
    val bytes: ByteArray?,
    val truncated: Boolean,
    val receivedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is SyncQuarantineEntity && objectId == other.objectId

    override fun hashCode(): Int = objectId.hashCode()
}
