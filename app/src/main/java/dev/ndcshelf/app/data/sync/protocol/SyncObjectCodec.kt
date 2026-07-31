package dev.ndcshelf.app.data.sync.protocol

import dev.ndcshelf.app.data.sync.crypto.Base64Url
import dev.ndcshelf.app.data.sync.crypto.SyncCrypto
import dev.ndcshelf.app.domain.sync.SYNC_PROTOCOL_MAJOR
import dev.ndcshelf.app.domain.sync.SYNC_PROTOCOL_VERSION
import dev.ndcshelf.app.domain.sync.SYNC_SUITE_ID
import dev.ndcshelf.app.domain.sync.SyncDeviceRegistry
import dev.ndcshelf.app.domain.sync.SyncRegistryDevice
import kotlinx.serialization.json.jsonObject

/**
 * SYNC_PROTOCOL.md 6節のencrypted envelopeを組み立て・検証する。
 * 検証順序はsize→suite→protocol→epoch→device authorization→signature→
 * object hash→AEAD→schemaで、失敗したobjectをdomainへ渡さない。
 */
object SyncObjectCodec {
    class SealedObject(
        val objectId: String,
        val bytes: ByteArray,
    )

    sealed interface OpenResult {
        class Valid(
            val header: WireProtectedHeader,
            val payload: WirePayload,
            val sender: SyncRegistryDevice,
        ) : OpenResult

        /** security-invalid。quarantine対象で、同期を停止する。 */
        class Invalid(
            val reason: String,
        ) : OpenResult
    }

    fun seal(
        payloadCanonical: ByteArray,
        libraryOpaqueId: String,
        epoch: Int,
        registryGeneration: Int,
        contentSubkey: ByteArray,
        encryptionCounter: Long,
        signingKeyId: ByteArray,
        sign: (ByteArray) -> ByteArray,
    ): SealedObject {
        val padded = SyncCrypto.padPlaintext(payloadCanonical)
        val header =
            WireProtectedHeader(
                protocolVersion = SYNC_PROTOCOL_VERSION,
                suite = SYNC_SUITE_ID,
                libraryOpaqueId = libraryOpaqueId,
                epoch = epoch,
                registryGeneration = registryGeneration,
                paddedLength = padded.size,
                signingDevicePublicKeyId = Base64Url.encode(signingKeyId),
            )
        val canonicalHeader = SyncWireJson.canonicalEncode(header)
        val nonce = SyncCrypto.encodeNonce(encryptionCounter)
        val ciphertext = SyncCrypto.aesGcmEncrypt(contentSubkey, nonce, padded, canonicalHeader)
        val objectId = SyncCrypto.computeObjectId(canonicalHeader, nonce, ciphertext)
        val signature = sign(SyncCrypto.objectSignatureBase(objectId))
        val envelope =
            WireSyncEnvelope(
                objectId = Base64Url.encode(objectId),
                protectedHeader = header,
                nonce = Base64Url.encode(nonce),
                ciphertext = Base64Url.encode(ciphertext),
                signature = Base64Url.encode(signature),
            )
        val bytes =
            SyncWireJson.strict
                .encodeToString(WireSyncEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
        return SealedObject(Base64Url.encode(objectId), bytes)
    }

    fun open(
        bytes: ByteArray,
        expectedObjectId: String,
        libraryOpaqueId: String,
        libraryId: ByteArray,
        registry: SyncDeviceRegistry,
        maxObjectSizeBytes: Long,
        epochKeyProvider: (Int) -> ByteArray?,
    ): OpenResult {
        if (bytes.size > maxObjectSizeBytes) return OpenResult.Invalid("object size limit exceeded")
        val rawText = bytes.toString(Charsets.UTF_8)
        val envelope =
            try {
                SyncWireJson.lenient.decodeFromString(WireSyncEnvelope.serializer(), rawText)
            } catch (_: Exception) {
                return OpenResult.Invalid("envelope parse failure")
            }
        val header = envelope.protectedHeader
        if (header.suite != SYNC_SUITE_ID) return OpenResult.Invalid("unknown suite")
        val major = header.protocolVersion.substringBefore('.').toIntOrNull()
        if (major != SYNC_PROTOCOL_MAJOR) return OpenResult.Invalid("unknown protocol major")
        if (header.libraryOpaqueId != libraryOpaqueId) return OpenResult.Invalid("library mismatch")
        if (header.epoch < 1 || header.registryGeneration < 1) {
            return OpenResult.Invalid("invalid epoch or generation")
        }
        if (header.registryGeneration > registry.registryGeneration) {
            return OpenResult.Invalid("registry generation is ahead of the verified registry")
        }
        val epochKey =
            epochKeyProvider(header.epoch)
                ?: return OpenResult.Invalid("epoch key unavailable")
        val sender =
            registry.devices.firstOrNull { device ->
                device.signingPublicKey.let { encoded ->
                    Base64Url.decode(encoded)?.let { der ->
                        Base64Url.encode(SyncCrypto.signingKeyId(der)) == header.signingDevicePublicKeyId
                    } == true
                }
            } ?: return OpenResult.Invalid("unauthorized signing key")
        sender.revokedAtGeneration?.let { revokedAt ->
            if (header.registryGeneration >= revokedAt) {
                return OpenResult.Invalid("object was created after device revocation")
            }
        }
        // 未知optional fieldを保持するため、canonical headerは受信raw JSONの
        // protectedHeader部分から再構成する。
        val canonicalHeader =
            canonicalSubObject(rawText, "protectedHeader")
                ?: return OpenResult.Invalid("header canonicalization failure")
        val nonce = Base64Url.decode(envelope.nonce)
        val ciphertext = Base64Url.decode(envelope.ciphertext)
        val signature = Base64Url.decode(envelope.signature)
        val declaredObjectId = Base64Url.decode(envelope.objectId)
        if (nonce == null || nonce.size != SyncCrypto.NONCE_BYTES || ciphertext == null ||
            signature == null || declaredObjectId == null
        ) {
            return OpenResult.Invalid("envelope field encoding failure")
        }
        if (header.paddedLength != ciphertext.size - SyncCrypto.GCM_TAG_BITS / 8) {
            return OpenResult.Invalid("padded length mismatch")
        }
        val objectId = SyncCrypto.computeObjectId(canonicalHeader, nonce, ciphertext)
        if (!SyncCrypto.constantTimeEquals(objectId, declaredObjectId)) {
            return OpenResult.Invalid("object hash mismatch")
        }
        if (Base64Url.encode(objectId) != expectedObjectId) {
            return OpenResult.Invalid("object id does not match its address")
        }
        val senderKeyDer =
            Base64Url.decode(sender.signingPublicKey)
                ?: return OpenResult.Invalid("sender key encoding failure")
        if (!SyncCrypto.verifySignature(senderKeyDer, SyncCrypto.objectSignatureBase(objectId), signature)) {
            return OpenResult.Invalid("signature verification failure")
        }
        val senderDeviceId =
            Base64Url.decode(sender.deviceId)?.takeIf { it.size == SyncCrypto.DEVICE_ID_BYTES }
                ?: return OpenResult.Invalid("sender device id encoding failure")
        val subkey =
            SyncCrypto.deriveContentSubkey(epochKey, libraryId, header.epoch, senderDeviceId)
        val padded =
            try {
                SyncCrypto.aesGcmDecrypt(subkey, nonce, ciphertext, canonicalHeader)
            } catch (_: Exception) {
                return OpenResult.Invalid("AEAD decryption failure")
            }
        val canonicalPayload =
            try {
                SyncCrypto.unpadPlaintext(padded)
            } catch (_: Exception) {
                return OpenResult.Invalid("plaintext framing failure")
            }
        val payload =
            decodeWirePayload(canonicalPayload)
                ?: return OpenResult.Invalid("payload schema failure")
        val payloadDeviceId =
            when (payload) {
                is WirePayload.Operations -> payload.payload.deviceId
                is WirePayload.Snapshot -> payload.payload.deviceId
            }
        if (payloadDeviceId != sender.deviceId) {
            return OpenResult.Invalid("payload device does not match the signing device")
        }
        val requiredCapabilities =
            when (payload) {
                is WirePayload.Operations -> payload.payload.requiredCapabilities
                is WirePayload.Snapshot -> payload.payload.requiredCapabilities
            }
        if (requiredCapabilities.any { it !in SUPPORTED_CAPABILITIES }) {
            return OpenResult.Invalid("unknown required capability")
        }
        return OpenResult.Valid(header, payload, sender)
    }

    /** raw JSONから指定fieldのsub-objectをcanonical化する（未知field保持）。 */
    fun canonicalSubObject(
        rawText: String,
        field: String,
    ): ByteArray? =
        try {
            val element = SyncWireJson.lenient.parseToJsonElement(rawText).jsonObject[field]
            element?.let { SyncWireJson.canonicalBytes(it.toString()) }
        } catch (_: Exception) {
            null
        }

    private val SUPPORTED_CAPABILITIES = emptySet<String>()
}
