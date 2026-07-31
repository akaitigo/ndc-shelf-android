package dev.ndcshelf.app.data.sync.crypto

import com.google.crypto.tink.subtle.EllipticCurves
import com.google.crypto.tink.subtle.Hkdf
import dev.ndcshelf.app.domain.sync.MAX_SYNC_OBJECT_PLAINTEXT_BYTES
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SYNC_PROTOCOL.md 6節のcontent暗号・署名・識別子のprimitive組み立て。
 * HKDF・DER検証はTink、AES-256-GCMとECDSA検証はplatform JCAを使い、
 * 暗号primitive・canonicalization・DER parserを独自実装しない。
 */
object SyncCrypto {
    private const val CONTENT_INFO_LABEL = "ndc-shelf-sync-v1/content"
    private const val OBJECT_SIGNATURE_LABEL = "ndc-shelf-sync-v1/object-signature"
    private const val EPOCH_KEY_SIGNATURE_LABEL = "ndc-shelf-sync-v1/epoch-key-signature"
    private const val REGISTRY_SIGNATURE_LABEL = "ndc-shelf-sync-v1/registry-signature"
    private const val HEAD_SIGNATURE_LABEL = "ndc-shelf-sync-v1/head-signature"
    private const val JOIN_REQUEST_MAC_LABEL = "ndc-shelf-sync-v1/join-request"
    private const val ENVELOPE_INVITE_MAC_LABEL = "ndc-shelf-sync-v1/envelope-invite-mac"
    private const val VERIFICATION_CODE_LABEL = "ndc-shelf-sync-v1/verification-code"

    const val EPOCH_KEY_BYTES = 32
    const val LIBRARY_ID_BYTES = 16
    const val DEVICE_ID_BYTES = 16
    const val NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128
    const val PADDING_UNIT_BYTES = 64 * 1024

    // P-256の位数n（SEC2）。署名r/sの範囲検証に使う。
    private val P256_ORDER =
        BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /** RFC 5869 extract-then-expandによるdevice content subkey導出（6節）。 */
    fun deriveContentSubkey(
        epochKey: ByteArray,
        libraryId: ByteArray,
        epoch: Int,
        deviceId: ByteArray,
    ): ByteArray {
        require(epochKey.size == EPOCH_KEY_BYTES)
        require(libraryId.size == LIBRARY_ID_BYTES)
        require(deviceId.size == DEVICE_ID_BYTES)
        require(epoch >= 1)
        val info =
            CONTENT_INFO_LABEL.toByteArray(Charsets.US_ASCII) +
                uint64Be(epoch.toLong()) +
                deviceId
        return Hkdf.computeHkdf("HMACSHA256", epochKey, libraryId, info, EPOCH_KEY_BYTES)
    }

    /** 32-bit zero prefix + big-endian 64-bit encryption counterの96-bit nonce。 */
    fun encodeNonce(encryptionCounter: Long): ByteArray {
        require(encryptionCounter >= 1) { "Encryption counter must be positive." }
        return ByteArray(4) + uint64Be(encryptionCounter)
    }

    fun uint64Be(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()

    fun uint32Be(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    /**
     * AEAD plaintext: uint32be(len) || canonicalJson || randomPadding。
     * 全体を最低64 KiB、以降64 KiB単位へ揃える（6節）。
     */
    fun padPlaintext(canonicalJson: ByteArray): ByteArray {
        require(canonicalJson.size <= MAX_SYNC_OBJECT_PLAINTEXT_BYTES) {
            "Sync object plaintext exceeds the 8 MiB limit."
        }
        val bodySize = 4 + canonicalJson.size
        val paddedSize =
            ((bodySize + PADDING_UNIT_BYTES - 1) / PADDING_UNIT_BYTES) * PADDING_UNIT_BYTES
        val padded = ByteArray(paddedSize)
        uint32Be(canonicalJson.size).copyInto(padded)
        canonicalJson.copyInto(padded, 4)
        randomBytes(paddedSize - bodySize).copyInto(padded, bodySize)
        return padded
    }

    /** 復号後plaintextからlength境界内のcanonical JSON bytesだけを取り出す。 */
    fun unpadPlaintext(padded: ByteArray): ByteArray {
        require(padded.size >= 4) { "Padded plaintext is truncated." }
        val length = ByteBuffer.wrap(padded, 0, 4).int
        require(length in 0..MAX_SYNC_OBJECT_PLAINTEXT_BYTES && length + 4 <= padded.size) {
            "Padded plaintext declares an invalid length."
        }
        return padded.copyOfRange(4, 4 + length)
    }

    fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == EPOCH_KEY_BYTES && nonce.size == NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == EPOCH_KEY_BYTES && nonce.size == NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    /** objectId = SHA-256(canonicalHeader || nonce12 || ciphertext)（6節）。 */
    fun computeObjectId(
        canonicalHeader: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(nonce.size == NONCE_BYTES)
        return sha256(canonicalHeader + nonce + ciphertext)
    }

    fun objectSignatureBase(objectId: ByteArray): ByteArray = labeledHashBase(OBJECT_SIGNATURE_LABEL, objectId)

    fun epochKeySignatureBase(envelopeId: ByteArray): ByteArray = labeledHashBase(EPOCH_KEY_SIGNATURE_LABEL, envelopeId)

    fun registrySignatureBase(registryHash: ByteArray): ByteArray = labeledHashBase(REGISTRY_SIGNATURE_LABEL, registryHash)

    fun headSignatureBase(headHash: ByteArray): ByteArray = labeledHashBase(HEAD_SIGNATURE_LABEL, headHash)

    private fun labeledHashBase(
        label: String,
        hash: ByteArray,
    ): ByteArray {
        require(hash.size == 32)
        return label.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + hash
    }

    /** signingDevicePublicKeyId = SHA-256(SubjectPublicKeyInfo DER)（6節）。 */
    fun signingKeyId(publicKeyDer: ByteArray): ByteArray = sha256(publicKeyDer)

    /**
     * strict ASN.1 DERのECDSA P-256/SHA-256検証。非canonical DER、末尾data、
     * P-256範囲外のr/sを拒否する（6節）。DER構造検証はTinkに委譲する。
     */
    fun verifySignature(
        publicKeyDer: ByteArray,
        data: ByteArray,
        signatureDer: ByteArray,
    ): Boolean {
        if (!EllipticCurves.isValidDerEncoding(signatureDer)) return false
        if (!signatureScalarsInRange(signatureDer)) return false
        return try {
            val key =
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
                    as ECPublicKey
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update(data)
            verifier.verify(signatureDer)
        } catch (_: Exception) {
            false
        }
    }

    /** Tinkの厳密DER検証を通過した署名からr/sを取り出し、[1, n-1]範囲を検証する。 */
    private fun signatureScalarsInRange(signatureDer: ByteArray): Boolean =
        try {
            // IEEE P1363形式（r||s各32byte）へはTinkの変換関数で正規化する。
            val ieee = EllipticCurves.ecdsaDer2Ieee(signatureDer, 64)
            val r = BigInteger(1, ieee.copyOfRange(0, 32))
            val s = BigInteger(1, ieee.copyOfRange(32, 64))
            r.signum() > 0 && s.signum() > 0 && r < P256_ORDER && s < P256_ORDER
        } catch (_: Exception) {
            false
        }

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun joinRequestMac(
        inviteSecret: ByteArray,
        canonicalRequest: ByteArray,
    ): ByteArray =
        hmacSha256(
            inviteSecret,
            JOIN_REQUEST_MAC_LABEL.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + canonicalRequest,
        )

    /** join envelopeを招待secretへ束縛するMAC。承認者のなりすましを防ぐ。 */
    fun envelopeInviteMac(
        inviteSecret: ByteArray,
        envelopeId: ByteArray,
    ): ByteArray {
        require(envelopeId.size == 32)
        return hmacSha256(
            inviteSecret,
            ENVELOPE_INVITE_MAC_LABEL.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + envelopeId,
        )
    }

    /** 双方の端末に表示する6桁verification code。 */
    fun verificationCode(
        inviteNonce: ByteArray,
        canonicalRequest: ByteArray,
    ): String {
        val digest =
            sha256(
                VERIFICATION_CODE_LABEL.toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0) +
                    inviteNonce +
                    canonicalRequest,
            )
        val value = ByteBuffer.wrap(digest, 0, 4).int.toLong() and 0xFFFFFFFFL
        return "%06d".format(value % 1_000_000L)
    }

    fun constantTimeEquals(
        first: ByteArray,
        second: ByteArray,
    ): Boolean = MessageDigest.isEqual(first, second)
}
