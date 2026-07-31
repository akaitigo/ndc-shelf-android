package dev.ndcshelf.app.data.sync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

/** 実Tinkを使ったRFC 9180 HPKE suiteのroundtripとnegative test。 */
class SyncHpkeTest {
    private val info = "ndc-shelf-sync-v1/epoch-key-test".toByteArray()
    private val aad = "{\"authorization\":true}".toByteArray()
    private val epochKey = ByteArray(32) { (it * 3).toByte() }

    @Test
    fun sealAndOpenRoundTripsAcrossKeyPairs() {
        val recipient = SyncHpke.generateRecipientKeyPair()
        assertEquals(SyncHpke.PUBLIC_KEY_BYTES, recipient.publicKey.size)
        assertEquals(SyncHpke.PRIVATE_KEY_BYTES, recipient.privateKey.size)
        val sealed = SyncHpke.seal(recipient.publicKey, info, aad, epochKey)
        val opened =
            SyncHpke.open(
                recipientPrivateKey = recipient.privateKey,
                recipientPublicKey = recipient.publicKey,
                encapsulatedKey = sealed.encapsulatedKey,
                info = info,
                aad = aad,
                ciphertext = sealed.ciphertext,
            )
        assertArrayEquals(epochKey, opened)
    }

    @Test
    fun openFailsForWrongRecipientInfoAadOrTamper() {
        val recipient = SyncHpke.generateRecipientKeyPair()
        val other = SyncHpke.generateRecipientKeyPair()
        val sealed = SyncHpke.seal(recipient.publicKey, info, aad, epochKey)

        assertThrows(GeneralSecurityException::class.java) {
            SyncHpke.open(other.privateKey, other.publicKey, sealed.encapsulatedKey, info, aad, sealed.ciphertext)
        }
        assertThrows(GeneralSecurityException::class.java) {
            SyncHpke.open(
                recipient.privateKey,
                recipient.publicKey,
                sealed.encapsulatedKey,
                "other-info".toByteArray(),
                aad,
                sealed.ciphertext,
            )
        }
        assertThrows(GeneralSecurityException::class.java) {
            SyncHpke.open(
                recipient.privateKey,
                recipient.publicKey,
                sealed.encapsulatedKey,
                info,
                "other-aad".toByteArray(),
                sealed.ciphertext,
            )
        }
        val tampered = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) {
            SyncHpke.open(recipient.privateKey, recipient.publicKey, sealed.encapsulatedKey, info, aad, tampered)
        }
    }

    @Test
    fun sealProducesFreshEncapsulationPerCall() {
        val recipient = SyncHpke.generateRecipientKeyPair()
        val first = SyncHpke.seal(recipient.publicKey, info, aad, epochKey)
        val second = SyncHpke.seal(recipient.publicKey, info, aad, epochKey)
        org.junit.Assert.assertFalse(first.encapsulatedKey.contentEquals(second.encapsulatedKey))
        org.junit.Assert.assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }
}
