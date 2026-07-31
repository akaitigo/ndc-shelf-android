package dev.ndcshelf.app.data.sync.crypto

/**
 * Keystore wrapping keyで暗号化した鍵blob。平文鍵をKeystore外へ
 * 永続化してはならない。nonceはKeystoreが生成した値をそのまま保存する。
 */
data class WrappedKeyBlob(
    val nonce: ByteArray,
    /** GCM tagを含むciphertext。 */
    val ciphertext: ByteArray,
    val keyAliasVersion: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is WrappedKeyBlob &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            keyAliasVersion == other.keyAliasVersion

    override fun hashCode(): Int = nonce.contentHashCode()
}

class SyncKeyUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 端末鍵の抽象。production実装はAndroid Keystore
 * （non-exportable P-256署名鍵とAES-256-GCM wrapping key）、JVMテストは
 * 同じ契約のin-memory実装で実crypto経路を検証する。
 */
interface SyncKeyManager {
    /** 署名鍵・wrapping keyを生成（存在すれば再利用）し、公開鍵DERを返す。 */
    fun ensureDeviceKeys(): ByteArray

    /** X.509 SubjectPublicKeyInfo DER。鍵未生成ならSyncKeyUnavailableException。 */
    fun signingPublicKeyDer(): ByteArray

    /** SHA256withECDSAのstrict DER署名。 */
    fun sign(data: ByteArray): ByteArray

    /**
     * wrapping key（AES-256-GCM、randomized encryption required）でHPKE秘密鍵
     * またはepoch keyを暗号化する。AADでkey種別・libraryId・key versionを認証する。
     */
    fun wrap(
        keyType: String,
        libraryId: String,
        keyVersion: Int,
        plaintext: ByteArray,
    ): WrappedKeyBlob

    fun unwrap(
        keyType: String,
        libraryId: String,
        keyVersion: Int,
        blob: WrappedKeyBlob,
    ): ByteArray

    /** hardware-backed（StrongBox含む）か。UI表示に使い、対応必須にしない。 */
    fun isHardwareBacked(): Boolean

    /** sign-out・全削除時に端末鍵を破棄する。 */
    fun destroyDeviceKeys()

    companion object {
        const val KEY_TYPE_HPKE_PRIVATE = "hpke-private"
        const val KEY_TYPE_EPOCH = "epoch"

        /** wrap AAD: 種別・library・versionを認証し、blobの取り違えを防ぐ。 */
        fun wrapAad(
            keyType: String,
            libraryId: String,
            keyVersion: Int,
        ): ByteArray =
            "ndc-shelf-sync-v1/wrapped-key".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0) +
                keyType.toByteArray(Charsets.UTF_8) +
                byteArrayOf(0) +
                libraryId.toByteArray(Charsets.UTF_8) +
                byteArrayOf(0) +
                SyncCrypto.uint32Be(keyVersion)
    }
}
