package dev.ndcshelf.app.data.sync.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore実装（ADR 0005）。P-256署名鍵とnon-exportable
 * AES-256-GCM wrapping keyをKeystoreへ置き、HPKE秘密鍵・epoch keyは
 * wrap済みblobだけをapp-private storageへ渡す。nonce生成はKeystoreの
 * randomized encryptionへ委ね、caller指定nonceを使わない。
 * Keystore operationはmain threadで呼び出さないこと。
 */
class AndroidKeystoreSyncKeyManager(
    private val signingAlias: String = DEFAULT_SIGNING_ALIAS,
    private val wrappingAlias: String = DEFAULT_WRAPPING_ALIAS,
) : SyncKeyManager {
    override fun ensureDeviceKeys(): ByteArray {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(signingAlias)) generateSigningKey()
        if (!keyStore.containsAlias(wrappingAlias)) generateWrappingKey()
        return signingPublicKeyDer()
    }

    override fun signingPublicKeyDer(): ByteArray {
        val certificate =
            loadKeyStore().getCertificate(signingAlias)
                ?: throw SyncKeyUnavailableException("Sync signing key is unavailable.")
        return certificate.publicKey.encoded
    }

    override fun sign(data: ByteArray): ByteArray {
        val privateKey =
            loadKeyStore().getKey(signingAlias, null) as? PrivateKey
                ?: throw SyncKeyUnavailableException("Sync signing key is unavailable.")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    override fun wrap(
        keyType: String,
        libraryId: String,
        keyVersion: Int,
        plaintext: ByteArray,
    ): WrappedKeyBlob {
        val cipher = Cipher.getInstance(WRAPPING_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(SyncKeyManager.wrapAad(keyType, libraryId, keyVersion))
        val ciphertext = cipher.doFinal(plaintext)
        return WrappedKeyBlob(
            nonce = cipher.iv,
            ciphertext = ciphertext,
            keyAliasVersion = KEY_ALIAS_VERSION,
        )
    }

    override fun unwrap(
        keyType: String,
        libraryId: String,
        keyVersion: Int,
        blob: WrappedKeyBlob,
    ): ByteArray {
        if (blob.keyAliasVersion != KEY_ALIAS_VERSION) {
            throw SyncKeyUnavailableException("Wrapped key uses an unknown key alias version.")
        }
        return try {
            val cipher = Cipher.getInstance(WRAPPING_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(SyncCrypto.GCM_TAG_BITS, blob.nonce),
            )
            cipher.updateAAD(SyncKeyManager.wrapAad(keyType, libraryId, keyVersion))
            cipher.doFinal(blob.ciphertext)
        } catch (error: SyncKeyUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw SyncKeyUnavailableException("Could not unwrap a sync key.", error)
        }
    }

    override fun isHardwareBacked(): Boolean =
        try {
            val privateKey = loadKeyStore().getKey(signingAlias, null) as? PrivateKey ?: return false
            val factory = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(privateKey, KeyInfo::class.java)
            @Suppress("DEPRECATION")
            keyInfo.isInsideSecureHardware
        } catch (_: Exception) {
            false
        }

    override fun destroyDeviceKeys() {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(signingAlias)) keyStore.deleteEntry(signingAlias)
        if (keyStore.containsAlias(wrappingAlias)) keyStore.deleteEntry(wrappingAlias)
    }

    private fun generateSigningKey() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val build = { strongBox: Boolean ->
            KeyGenParameterSpec
                .Builder(signingAlias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
        }
        try {
            generator.initialize(build(true))
            generator.generateKeyPair()
        } catch (_: Exception) {
            // StrongBox非対応端末はTEE/software Keystoreへfallbackする（ADR 0005）。
            generator.initialize(build(false))
            generator.generateKeyPair()
        }
    }

    private fun generateWrappingKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val build = { strongBox: Boolean ->
            KeyGenParameterSpec
                .Builder(wrappingAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
        }
        try {
            generator.init(build(true))
            generator.generateKey()
        } catch (_: Exception) {
            generator.init(build(false))
            generator.generateKey()
        }
    }

    private fun wrappingKey(): SecretKey =
        loadKeyStore().getKey(wrappingAlias, null) as? SecretKey
            ?: throw SyncKeyUnavailableException("Sync wrapping key is unavailable.")

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS_VERSION = 1
        const val DEFAULT_SIGNING_ALIAS = "ndc-shelf-sync-signing-v1"
        const val DEFAULT_WRAPPING_ALIAS = "ndc-shelf-sync-wrapping-v1"
    }
}
