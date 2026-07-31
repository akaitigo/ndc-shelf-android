package dev.ndcshelf.app.data.sync.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.internal.HpkeContext
import com.google.crypto.tink.hybrid.internal.HpkeKemKeyFactory
import com.google.crypto.tink.hybrid.internal.HpkePrimitiveFactory
import com.google.crypto.tink.hybrid.internal.NdcShelfHpkeSender
import com.google.crypto.tink.subtle.EllipticCurves
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * RFC 9180 HPKE base mode `DHKEM(P-256, HKDF-SHA256) / HKDF-SHA256 /
 * AES-256-GCM`（SYNC_PROTOCOL.md 6.1節）。実装はGoogle Tinkへ委譲し、
 * KEM・key schedule・AEADを独自実装しない。
 */
object SyncHpke {
    const val PUBLIC_KEY_BYTES = 65
    const val PRIVATE_KEY_BYTES = 32

    private val KEM_ID = HpkeParameters.KemId.DHKEM_P256_HKDF_SHA256
    private val KDF_ID = HpkeParameters.KdfId.HKDF_SHA256
    private val AEAD_ID = HpkeParameters.AeadId.AES_256_GCM

    class RecipientKeyPair(
        /** RFC 9180 SerializePublicKeyの65-byte uncompressed SEC1。 */
        val publicKey: ByteArray,
        /** 32-byte big-endian秘密scalar。使用後にzeroizeする。 */
        val privateKey: ByteArray,
    )

    class Sealed(
        val encapsulatedKey: ByteArray,
        val ciphertext: ByteArray,
    )

    fun generateRecipientKeyPair(): RecipientKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey =
            EllipticCurves.pointEncode(
                EllipticCurves.CurveType.NIST_P256,
                EllipticCurves.PointFormatType.UNCOMPRESSED,
                (keyPair.public as ECPublicKey).w,
            )
        val scalar = (keyPair.private as ECPrivateKey).s.toByteArray()
        val privateKey = ByteArray(PRIVATE_KEY_BYTES)
        val copyLength = minOf(scalar.size, PRIVATE_KEY_BYTES)
        scalar.copyInto(
            privateKey,
            destinationOffset = PRIVATE_KEY_BYTES - copyLength,
            startIndex = scalar.size - copyLength,
        )
        scalar.fill(0)
        check(publicKey.size == PUBLIC_KEY_BYTES)
        return RecipientKeyPair(publicKey, privateKey)
    }

    fun seal(
        recipientPublicKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): Sealed {
        require(recipientPublicKey.size == PUBLIC_KEY_BYTES)
        val context =
            NdcShelfHpkeSender.createSenderContext(
                recipientPublicKey,
                KEM_ID,
                KDF_ID,
                AEAD_ID,
                info,
            )
        return Sealed(context.encapsulatedKey, context.seal(plaintext, aad))
    }

    fun open(
        recipientPrivateKey: ByteArray,
        recipientPublicKey: ByteArray,
        encapsulatedKey: ByteArray,
        info: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(recipientPrivateKey.size == PRIVATE_KEY_BYTES)
        require(recipientPublicKey.size == PUBLIC_KEY_BYTES)
        val parameters =
            HpkeParameters
                .builder()
                .setKemId(KEM_ID)
                .setKdfId(KDF_ID)
                .setAeadId(AEAD_ID)
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .build()
        val publicKey = HpkePublicKey.create(parameters, Bytes.copyFrom(recipientPublicKey), null)
        val privateKey =
            HpkePrivateKey.create(
                publicKey,
                SecretBytes.copyFrom(recipientPrivateKey, InsecureSecretKeyAccess.get()),
            )
        val context =
            HpkeContext.createRecipientContext(
                encapsulatedKey,
                HpkeKemKeyFactory.createPrivate(privateKey),
                HpkePrimitiveFactory.createKem(KEM_ID),
                HpkePrimitiveFactory.createKdf(KDF_ID),
                HpkePrimitiveFactory.createAead(AEAD_ID),
                info,
            )
        return context.open(ciphertext, aad)
    }
}
