package dev.ndcshelf.app.data.sync.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * JVMテスト用のSyncKeyManager。Keystoreの契約（non-exportable相当の
 * 保管・AAD認証wrap）をin-memory JCA鍵で再現し、実crypto経路を検証する。
 */
class FakeSyncKeyManager : SyncKeyManager {
    private var keyPair: KeyPair? = null
    private var wrappingKey: SecretKey? = null

    var ensureCount: Int = 0
        private set
    var signCount: Int = 0
        private set
    var destroyed: Boolean = false
        private set

    override fun ensureDeviceKeys(): ByteArray {
        ensureCount += 1
        if (keyPair == null) {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            keyPair = generator.generateKeyPair()
            val aes = KeyGenerator.getInstance("AES")
            aes.init(256)
            wrappingKey = aes.generateKey()
            destroyed = false
        }
        return signingPublicKeyDer()
    }

    override fun signingPublicKeyDer(): ByteArray = (keyPair ?: throw SyncKeyUnavailableException("keys missing")).public.encoded

    override fun sign(data: ByteArray): ByteArray {
        signCount += 1
        val pair = keyPair ?: throw SyncKeyUnavailableException("keys missing")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(pair.private)
        signature.update(data)
        return signature.sign()
    }

    override fun wrap(
        keyType: String,
        libraryId: String,
        keyVersion: Int,
        plaintext: ByteArray,
    ): WrappedKeyBlob {
        val key = wrappingKey ?: throw SyncKeyUnavailableException("keys missing")
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(SyncKeyManager.wrapAad(keyType, libraryId, keyVersion))
        return WrappedKeyBlob(nonce, cipher.doFinal(plaintext), keyAliasVersion = 1)
    }

    override fun unwrap(
        keyType: String,
        libraryId: String,
        keyVersion: Int,
        blob: WrappedKeyBlob,
    ): ByteArray {
        val key = wrappingKey ?: throw SyncKeyUnavailableException("keys missing")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.nonce))
            cipher.updateAAD(SyncKeyManager.wrapAad(keyType, libraryId, keyVersion))
            cipher.doFinal(blob.ciphertext)
        } catch (error: Exception) {
            throw SyncKeyUnavailableException("unwrap failed", error)
        }
    }

    override fun isHardwareBacked(): Boolean = false

    override fun destroyDeviceKeys() {
        keyPair = null
        wrappingKey = null
        destroyed = true
    }
}
