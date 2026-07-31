package dev.ndcshelf.app.data.sync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {
    private val epochKey = ByteArray(32) { it.toByte() }
    private val libraryId = ByteArray(16) { (it + 1).toByte() }
    private val deviceA = ByteArray(16) { 0x41 }
    private val deviceB = ByteArray(16) { 0x42 }

    @Test
    fun contentSubkeyIsDeterministicAndDomainSeparated() {
        val subkeyA = SyncCrypto.deriveContentSubkey(epochKey, libraryId, 1, deviceA)
        assertArrayEquals(subkeyA, SyncCrypto.deriveContentSubkey(epochKey, libraryId, 1, deviceA))
        assertEquals(32, subkeyA.size)
        // device・epoch・libraryのどれが変わってもsubkeyが変わる。
        assertFalse(subkeyA.contentEquals(SyncCrypto.deriveContentSubkey(epochKey, libraryId, 1, deviceB)))
        assertFalse(subkeyA.contentEquals(SyncCrypto.deriveContentSubkey(epochKey, libraryId, 2, deviceA)))
        assertFalse(
            subkeyA.contentEquals(
                SyncCrypto.deriveContentSubkey(epochKey, ByteArray(16) { 9 }, 1, deviceA),
            ),
        )
    }

    @Test
    fun nonceEncodesCounterAsZeroPrefixedBigEndian() {
        val nonce = SyncCrypto.encodeNonce(1)
        assertEquals(12, nonce.size)
        assertArrayEquals(ByteArray(11), nonce.copyOfRange(0, 11))
        assertEquals(1, nonce[11].toInt())
        val max = SyncCrypto.encodeNonce(Long.MAX_VALUE)
        assertEquals(0x7F, max[4].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.encodeNonce(0) }
    }

    @Test
    fun paddingAlignsTo64KiBAndRoundTrips() {
        val payload = "{\"kind\":\"operations\"}".toByteArray()
        val padded = SyncCrypto.padPlaintext(payload)
        assertEquals(64 * 1024, padded.size)
        assertArrayEquals(payload, SyncCrypto.unpadPlaintext(padded))
        val big = ByteArray(64 * 1024)
        assertEquals(128 * 1024, SyncCrypto.padPlaintext(big).size)
    }

    @Test
    fun unpadRejectsOutOfBoundsLength() {
        val padded = SyncCrypto.padPlaintext("{}".toByteArray())
        padded[0] = 0x7F
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.unpadPlaintext(padded) }
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.unpadPlaintext(ByteArray(3)) }
    }

    @Test
    fun paddingRejectsPayloadOverEightMiB() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncCrypto.padPlaintext(ByteArray(8 * 1024 * 1024 + 1))
        }
    }

    @Test
    fun aesGcmDetectsTamperedCiphertextNonceAndAad() {
        val key = ByteArray(32) { 7 }
        val nonce = SyncCrypto.encodeNonce(42)
        val aad = "header".toByteArray()
        val ciphertext = SyncCrypto.aesGcmEncrypt(key, nonce, "secret".toByteArray(), aad)
        assertArrayEquals(
            "secret".toByteArray(),
            SyncCrypto.aesGcmDecrypt(key, nonce, ciphertext, aad),
        )
        val flipped = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { SyncCrypto.aesGcmDecrypt(key, nonce, flipped, aad) }
        assertThrows(Exception::class.java) {
            SyncCrypto.aesGcmDecrypt(key, SyncCrypto.encodeNonce(43), ciphertext, aad)
        }
        assertThrows(Exception::class.java) {
            SyncCrypto.aesGcmDecrypt(key, nonce, ciphertext, "other".toByteArray())
        }
    }

    @Test
    fun signatureVerificationRejectsTamperAndNonCanonicalDer() {
        val keyManager = FakeSyncKeyManager()
        val publicKey = keyManager.ensureDeviceKeys()
        val objectId = SyncCrypto.sha256("object".toByteArray())
        val base = SyncCrypto.objectSignatureBase(objectId)
        val signature = keyManager.sign(base)

        assertTrue(SyncCrypto.verifySignature(publicKey, base, signature))
        // 署名対象・署名bytesの改ざんを拒否する。
        assertFalse(
            SyncCrypto.verifySignature(
                publicKey,
                SyncCrypto.objectSignatureBase(SyncCrypto.sha256("other".toByteArray())),
                signature,
            ),
        )
        val flipped = signature.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse(SyncCrypto.verifySignature(publicKey, base, flipped))
        // 末尾dataの付加（非canonical DER）を拒否する。
        assertFalse(SyncCrypto.verifySignature(publicKey, base, signature + byteArrayOf(0)))
        // 別鍵での検証を拒否する。
        val otherKey = FakeSyncKeyManager().ensureDeviceKeys()
        assertFalse(SyncCrypto.verifySignature(otherKey, base, signature))
    }

    @Test
    fun verificationCodeIsSixDigitsAndInputSensitive() {
        val nonce = ByteArray(16) { 3 }
        val request = "{\"deviceId\":\"a\"}".toByteArray()
        val code = SyncCrypto.verificationCode(nonce, request)
        assertEquals(6, code.length)
        assertTrue(code.all(Char::isDigit))
        assertEquals(code, SyncCrypto.verificationCode(nonce, request))
        assertFalse(code == SyncCrypto.verificationCode(nonce, "{\"deviceId\":\"b\"}".toByteArray()))
    }

    @Test
    fun base64UrlRejectsPaddingAndStandardAlphabet() {
        val bytes = ByteArray(20) { it.toByte() }
        val encoded = Base64Url.encode(bytes)
        assertFalse(encoded.contains('='))
        assertArrayEquals(bytes, Base64Url.decode(encoded))
        assertNull(Base64Url.decode("$encoded=="))
        assertNull(Base64Url.decode("a+b/c"))
        assertNull(Base64Url.decode("with space"))
    }
}
