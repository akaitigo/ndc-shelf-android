package dev.ndcshelf.app.data.sync.protocol

import com.google.crypto.tink.subtle.Hkdf
import dev.ndcshelf.app.data.sync.crypto.Base64Url
import dev.ndcshelf.app.data.sync.crypto.SyncCrypto
import dev.ndcshelf.app.data.sync.crypto.SyncHpke
import dev.ndcshelf.app.domain.sync.SYNC_PROTOCOL_MAJOR
import dev.ndcshelf.app.domain.sync.SYNC_SUITE_ID
import dev.ndcshelf.app.domain.sync.SyncDeviceRegistry
import dev.ndcshelf.app.domain.sync.SyncLibraryHead
import dev.ndcshelf.app.domain.sync.SyncRegistryDevice

/**
 * 署名済みdevice registry・head・参加リクエスト・HPKE device key envelope
 * （SYNC_PROTOCOL.md 6.1、8、9節）のencode・検証。
 */
object SyncTrustCodec {
    private const val EPOCH_KEY_INFO_LABEL = "ndc-shelf-sync-v1/epoch-key"
    private const val DEVICE_NAME_INFO_LABEL = "ndc-shelf-sync-v1/device-name"
    private const val JOIN_NAME_INFO_LABEL = "ndc-shelf-sync-v1/join-name"
    private const val LIBRARY_ID_INFO_LABEL = "ndc-shelf-sync-v1/library-id"
    const val MAX_DEVICE_NAME_LENGTH = 64
    const val MAX_TRUST_DOCUMENT_BYTES = 1 * 1024 * 1024

    class SignedDocument(
        val bytes: ByteArray,
        val hash: ByteArray,
    )

    // ---- Registry ----

    fun encodeSignedRegistry(
        registry: WireRegistry,
        signerKeyId: ByteArray,
        sign: (ByteArray) -> ByteArray,
    ): SignedDocument {
        val canonical = SyncWireJson.canonicalEncode(registry)
        val hash = SyncCrypto.sha256(canonical)
        val signed =
            WireSignedRegistry(
                registry = registry,
                signedByKeyId = Base64Url.encode(signerKeyId),
                signature = Base64Url.encode(sign(SyncCrypto.registrySignatureBase(hash))),
            )
        val bytes =
            SyncWireJson.strict
                .encodeToString(WireSignedRegistry.serializer(), signed)
                .toByteArray(Charsets.UTF_8)
        return SignedDocument(bytes, hash)
    }

    class VerifiedRegistry(
        val registry: WireRegistry,
        val hash: ByteArray,
        val signedByKeyId: String,
    )

    /**
     * registry文書の署名を検証する。署名者の鍵はauthorizedByが提供する
     * （generation 1は自己署名、以後は前generationのactive deviceが署名）。
     */
    fun verifySignedRegistry(
        bytes: ByteArray,
        expectedGeneration: Int,
        expectedLibraryOpaqueId: String,
        authorizedBy: (signedByKeyId: String) -> ByteArray?,
    ): VerifiedRegistry? {
        if (bytes.size > MAX_TRUST_DOCUMENT_BYTES) return null
        val rawText = bytes.toString(Charsets.UTF_8)
        val signed =
            try {
                SyncWireJson.lenient.decodeFromString(WireSignedRegistry.serializer(), rawText)
            } catch (_: Exception) {
                return null
            }
        val registry = signed.registry
        if (registry.protocolVersion.substringBefore('.').toIntOrNull() != SYNC_PROTOCOL_MAJOR) return null
        if (registry.suite != SYNC_SUITE_ID) return null
        if (registry.registryGeneration != expectedGeneration) return null
        if (registry.libraryOpaqueId != expectedLibraryOpaqueId) return null
        if (registry.epoch < 1) return null
        if (registry.devices.isEmpty()) return null
        val canonical = SyncObjectCodec.canonicalSubObject(rawText, "registry") ?: return null
        val hash = SyncCrypto.sha256(canonical)
        val signerKeyDer = authorizedBy(signed.signedByKeyId) ?: return null
        val signature = Base64Url.decode(signed.signature) ?: return null
        if (!SyncCrypto.verifySignature(signerKeyDer, SyncCrypto.registrySignatureBase(hash), signature)) {
            return null
        }
        return VerifiedRegistry(registry, hash, signed.signedByKeyId)
    }

    fun toDomainRegistry(
        registry: WireRegistry,
        decryptName: (WireRegistryDevice) -> String?,
    ): SyncDeviceRegistry =
        SyncDeviceRegistry(
            libraryOpaqueId = registry.libraryOpaqueId,
            registryGeneration = registry.registryGeneration,
            epoch = registry.epoch,
            devices =
                registry.devices.map { device ->
                    SyncRegistryDevice(
                        deviceId = device.deviceId,
                        name = decryptName(device) ?: "",
                        signingPublicKey = device.signingPublicKey,
                        hpkePublicKey = device.hpkePublicKey,
                        addedAtGeneration = device.addedAtGeneration,
                        revokedAtGeneration = device.revokedAtGeneration,
                    )
                },
        )

    // ---- Head ----

    fun encodeSignedHead(
        head: WireHead,
        signerKeyId: ByteArray,
        sign: (ByteArray) -> ByteArray,
    ): SignedDocument {
        val canonical = SyncWireJson.canonicalEncode(head)
        val hash = SyncCrypto.sha256(canonical)
        val signed =
            WireSignedHead(
                head = head,
                signedByKeyId = Base64Url.encode(signerKeyId),
                signature = Base64Url.encode(sign(SyncCrypto.headSignatureBase(hash))),
            )
        val bytes =
            SyncWireJson.strict
                .encodeToString(WireSignedHead.serializer(), signed)
                .toByteArray(Charsets.UTF_8)
        return SignedDocument(bytes, hash)
    }

    class VerifiedHead(
        val head: WireHead,
        val hash: ByteArray,
    )

    fun verifySignedHead(
        bytes: ByteArray,
        expectedLibraryOpaqueId: String,
        signerKeyFor: (signedByKeyId: String) -> ByteArray?,
    ): VerifiedHead? {
        if (bytes.size > MAX_TRUST_DOCUMENT_BYTES) return null
        val rawText = bytes.toString(Charsets.UTF_8)
        val signed =
            try {
                SyncWireJson.lenient.decodeFromString(WireSignedHead.serializer(), rawText)
            } catch (_: Exception) {
                return null
            }
        val head = signed.head
        if (head.protocolVersion.substringBefore('.').toIntOrNull() != SYNC_PROTOCOL_MAJOR) return null
        if (head.libraryOpaqueId != expectedLibraryOpaqueId) return null
        if ((head.generation.toLongOrNull() ?: 0) < 1) return null
        if (head.epoch < 1 || head.registryGeneration < 1) return null
        val canonical = SyncObjectCodec.canonicalSubObject(rawText, "head") ?: return null
        val hash = SyncCrypto.sha256(canonical)
        val signerKeyDer = signerKeyFor(signed.signedByKeyId) ?: return null
        val signature = Base64Url.decode(signed.signature) ?: return null
        if (!SyncCrypto.verifySignature(signerKeyDer, SyncCrypto.headSignatureBase(hash), signature)) {
            return null
        }
        return VerifiedHead(head, hash)
    }

    fun toDomainHead(head: WireHead): SyncLibraryHead =
        SyncLibraryHead(
            libraryOpaqueId = head.libraryOpaqueId,
            generation = requireNotNull(head.generation.toLongOrNull()),
            epoch = head.epoch,
            registryGeneration = head.registryGeneration,
            registryHash = head.registryHash,
            deviceLogHeads = head.deviceLogHeads,
            snapshotObjectId = head.snapshotObjectId,
        )

    // ---- Device name encryption ----

    /** registryへ置く端末名はepochごとのname keyで暗号化する（平文名を禁止）。 */
    fun deviceNameKey(
        epochKey: ByteArray,
        libraryId: ByteArray,
        epoch: Int,
    ): ByteArray =
        Hkdf.computeHkdf(
            "HMACSHA256",
            epochKey,
            libraryId,
            DEVICE_NAME_INFO_LABEL.toByteArray(Charsets.US_ASCII) + SyncCrypto.uint64Be(epoch.toLong()),
            SyncCrypto.EPOCH_KEY_BYTES,
        )

    fun encryptDeviceName(
        nameKey: ByteArray,
        deviceId: String,
        name: String,
    ): Pair<String, String> {
        val nonce = SyncCrypto.randomBytes(SyncCrypto.NONCE_BYTES)
        val ciphertext =
            SyncCrypto.aesGcmEncrypt(
                nameKey,
                nonce,
                name.take(MAX_DEVICE_NAME_LENGTH).toByteArray(Charsets.UTF_8),
                deviceId.toByteArray(Charsets.UTF_8),
            )
        return Base64Url.encode(nonce) to Base64Url.encode(ciphertext)
    }

    fun decryptDeviceName(
        nameKey: ByteArray,
        deviceId: String,
        nameNonce: String,
        nameCiphertext: String,
    ): String? =
        try {
            val nonce = Base64Url.decodeOrThrow(nameNonce)
            val ciphertext = Base64Url.decodeOrThrow(nameCiphertext)
            SyncCrypto
                .aesGcmDecrypt(nameKey, nonce, ciphertext, deviceId.toByteArray(Charsets.UTF_8))
                .toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }

    /** join requestの端末名は招待secret由来のkeyで暗号化する。 */
    fun joinNameKey(
        inviteSecret: ByteArray,
        inviteNonce: ByteArray,
    ): ByteArray =
        Hkdf.computeHkdf(
            "HMACSHA256",
            inviteSecret,
            inviteNonce,
            JOIN_NAME_INFO_LABEL.toByteArray(Charsets.US_ASCII),
            SyncCrypto.EPOCH_KEY_BYTES,
        )

    /**
     * joinerへlibraryId（HKDF salt）を渡すための、招待secret由来のkey。
     * 招待secretはout-of-bandで渡すため、backendはlibraryIdを観測できない。
     */
    fun libraryIdKey(
        inviteSecret: ByteArray,
        inviteNonce: ByteArray,
    ): ByteArray =
        Hkdf.computeHkdf(
            "HMACSHA256",
            inviteSecret,
            inviteNonce,
            LIBRARY_ID_INFO_LABEL.toByteArray(Charsets.US_ASCII),
            SyncCrypto.EPOCH_KEY_BYTES,
        )

    fun encryptLibraryId(
        key: ByteArray,
        recipientDeviceId: String,
        libraryIdBytes: ByteArray,
    ): Pair<String, String> {
        require(libraryIdBytes.size == SyncCrypto.LIBRARY_ID_BYTES)
        val nonce = SyncCrypto.randomBytes(SyncCrypto.NONCE_BYTES)
        val ciphertext =
            SyncCrypto.aesGcmEncrypt(
                key,
                nonce,
                libraryIdBytes,
                recipientDeviceId.toByteArray(Charsets.UTF_8),
            )
        return Base64Url.encode(nonce) to Base64Url.encode(ciphertext)
    }

    fun decryptLibraryId(
        key: ByteArray,
        recipientDeviceId: String,
        nonce: String,
        ciphertext: String,
    ): ByteArray? =
        try {
            SyncCrypto
                .aesGcmDecrypt(
                    key,
                    Base64Url.decodeOrThrow(nonce),
                    Base64Url.decodeOrThrow(ciphertext),
                    recipientDeviceId.toByteArray(Charsets.UTF_8),
                ).takeIf { it.size == SyncCrypto.LIBRARY_ID_BYTES }
        } catch (_: Exception) {
            null
        }

    // ---- Join request ----

    fun encodeJoinRequest(
        request: WireJoinRequest,
        inviteSecret: ByteArray,
    ): ByteArray {
        val canonical = SyncWireJson.canonicalEncode(request)
        val signed =
            WireMacJoinRequest(
                request = request,
                mac = Base64Url.encode(SyncCrypto.joinRequestMac(inviteSecret, canonical)),
            )
        return SyncWireJson.strict
            .encodeToString(WireMacJoinRequest.serializer(), signed)
            .toByteArray(Charsets.UTF_8)
    }

    class VerifiedJoinRequest(
        val request: WireJoinRequest,
        val canonicalRequest: ByteArray,
    )

    fun verifyJoinRequest(
        bytes: ByteArray,
        inviteSecret: ByteArray,
        expectedNonce: String,
        expectedLibraryOpaqueId: String,
    ): VerifiedJoinRequest? {
        if (bytes.size > MAX_TRUST_DOCUMENT_BYTES) return null
        val rawText = bytes.toString(Charsets.UTF_8)
        val signed =
            try {
                SyncWireJson.lenient.decodeFromString(WireMacJoinRequest.serializer(), rawText)
            } catch (_: Exception) {
                return null
            }
        val request = signed.request
        if (request.protocolVersion.substringBefore('.').toIntOrNull() != SYNC_PROTOCOL_MAJOR) return null
        if (request.suite != SYNC_SUITE_ID) return null
        if (request.libraryOpaqueId != expectedLibraryOpaqueId) return null
        if (request.inviteNonce != expectedNonce) return null
        val canonical = SyncObjectCodec.canonicalSubObject(rawText, "request") ?: return null
        val mac = Base64Url.decode(signed.mac) ?: return null
        if (!SyncCrypto.constantTimeEquals(mac, SyncCrypto.joinRequestMac(inviteSecret, canonical))) {
            return null
        }
        return VerifiedJoinRequest(request, canonical)
    }

    // ---- HPKE device key envelope (6.1) ----

    fun epochKeyInfo(
        epoch: Int,
        recipientDeviceId: ByteArray,
        registryHash: ByteArray,
        headHash: ByteArray,
    ): ByteArray {
        require(recipientDeviceId.size == SyncCrypto.DEVICE_ID_BYTES)
        require(registryHash.size == 32 && headHash.size == 32)
        return EPOCH_KEY_INFO_LABEL.toByteArray(Charsets.US_ASCII) +
            SyncCrypto.uint64Be(epoch.toLong()) +
            recipientDeviceId +
            registryHash +
            headHash
    }

    fun sealEpochKeyEnvelope(
        authorization: WireKeyAuthorization,
        recipientHpkePublicKey: ByteArray,
        epochKey: ByteArray,
        sign: (ByteArray) -> ByteArray,
        inviteSecret: ByteArray? = null,
    ): ByteArray {
        val canonicalAuth = SyncWireJson.canonicalEncode(authorization)
        val info =
            epochKeyInfo(
                epoch = authorization.epoch,
                recipientDeviceId = Base64Url.decodeOrThrow(authorization.recipientDeviceId),
                registryHash = Base64Url.decodeOrThrow(authorization.registryHash),
                headHash = Base64Url.decodeOrThrow(authorization.trustedHeadHash),
            )
        val sealed = SyncHpke.seal(recipientHpkePublicKey, info, canonicalAuth, epochKey)
        val envelopeId =
            SyncCrypto.sha256(canonicalAuth + sealed.encapsulatedKey + sealed.ciphertext)
        val envelope =
            WireKeyEnvelope(
                authorization = authorization,
                enc = Base64Url.encode(sealed.encapsulatedKey),
                ciphertext = Base64Url.encode(sealed.ciphertext),
                envelopeId = Base64Url.encode(envelopeId),
                signature = Base64Url.encode(sign(SyncCrypto.epochKeySignatureBase(envelopeId))),
                inviteMac =
                    inviteSecret?.let {
                        Base64Url.encode(SyncCrypto.envelopeInviteMac(it, envelopeId))
                    },
            )
        return SyncWireJson.strict
            .encodeToString(WireKeyEnvelope.serializer(), envelope)
            .toByteArray(Charsets.UTF_8)
    }

    class OpenedEpochKeyEnvelope(
        val authorization: WireKeyAuthorization,
        val epochKey: ByteArray,
        val envelopeId: String,
    )

    /**
     * recipient側の検証と復号。署名・envelope hash・宛先・期限・epoch範囲を
     * 検証し、senderの署名鍵はcallerが検証済みregistryから解決する。
     * invite nonceの一回性はcallerが管理する。
     */
    fun openEpochKeyEnvelope(
        bytes: ByteArray,
        recipientDeviceId: String,
        recipientHpkePrivateKey: ByteArray,
        recipientHpkePublicKey: ByteArray,
        nowMillis: Long,
        senderKeyFor: (senderSigningKeyId: String) -> ByteArray?,
        requiredInviteSecret: ByteArray? = null,
    ): OpenedEpochKeyEnvelope? {
        if (bytes.size > MAX_TRUST_DOCUMENT_BYTES) return null
        val rawText = bytes.toString(Charsets.UTF_8)
        val envelope =
            try {
                SyncWireJson.lenient.decodeFromString(WireKeyEnvelope.serializer(), rawText)
            } catch (_: Exception) {
                return null
            }
        val authorization = envelope.authorization
        if (authorization.protocolVersion.substringBefore('.').toIntOrNull() != SYNC_PROTOCOL_MAJOR) return null
        if (authorization.suite != SYNC_SUITE_ID) return null
        if (authorization.recipientDeviceId != recipientDeviceId) return null
        if (authorization.epoch < 1 || authorization.registryGeneration < 1) return null
        val expiresAt = SyncWireTime.decode(authorization.expiresAt) ?: return null
        if (nowMillis > expiresAt) return null
        if (authorization.recipientHpkePublicKey != Base64Url.encode(recipientHpkePublicKey)) return null
        val canonicalAuth = SyncObjectCodec.canonicalSubObject(rawText, "authorization") ?: return null
        val enc = Base64Url.decode(envelope.enc) ?: return null
        val ciphertext = Base64Url.decode(envelope.ciphertext) ?: return null
        val declaredEnvelopeId = Base64Url.decode(envelope.envelopeId) ?: return null
        val envelopeId = SyncCrypto.sha256(canonicalAuth + enc + ciphertext)
        if (!SyncCrypto.constantTimeEquals(envelopeId, declaredEnvelopeId)) return null
        if (requiredInviteSecret != null) {
            val inviteMac = envelope.inviteMac?.let(Base64Url::decode) ?: return null
            val expectedMac = SyncCrypto.envelopeInviteMac(requiredInviteSecret, envelopeId)
            if (!SyncCrypto.constantTimeEquals(inviteMac, expectedMac)) return null
        }
        val senderKeyDer = senderKeyFor(authorization.senderSigningKeyId) ?: return null
        val signature = Base64Url.decode(envelope.signature) ?: return null
        if (!SyncCrypto.verifySignature(senderKeyDer, SyncCrypto.epochKeySignatureBase(envelopeId), signature)) {
            return null
        }
        val info =
            try {
                epochKeyInfo(
                    epoch = authorization.epoch,
                    recipientDeviceId = Base64Url.decodeOrThrow(authorization.recipientDeviceId),
                    registryHash = Base64Url.decodeOrThrow(authorization.registryHash),
                    headHash = Base64Url.decodeOrThrow(authorization.trustedHeadHash),
                )
            } catch (_: Exception) {
                return null
            }
        val epochKey =
            try {
                SyncHpke.open(
                    recipientPrivateKey = recipientHpkePrivateKey,
                    recipientPublicKey = recipientHpkePublicKey,
                    encapsulatedKey = enc,
                    info = info,
                    aad = canonicalAuth,
                    ciphertext = ciphertext,
                )
            } catch (_: Exception) {
                return null
            }
        if (epochKey.size != SyncCrypto.EPOCH_KEY_BYTES) return null
        return OpenedEpochKeyEnvelope(authorization, epochKey, envelope.envelopeId)
    }
}
