package dev.ndcshelf.app.data.sync.protocol

import dev.ndcshelf.app.data.sync.crypto.Base64Url
import dev.ndcshelf.app.data.sync.crypto.FakeSyncKeyManager
import dev.ndcshelf.app.data.sync.crypto.SyncCrypto
import dev.ndcshelf.app.data.sync.crypto.SyncHpke
import dev.ndcshelf.app.domain.sync.SYNC_PROTOCOL_VERSION
import dev.ndcshelf.app.domain.sync.SYNC_SUITE_ID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTrustCodecTest {
    private val sender = FakeSyncKeyManager()
    private val senderPublicKey = sender.ensureDeviceKeys()
    private val senderKeyId = Base64Url.encode(SyncCrypto.signingKeyId(senderPublicKey))
    private val libraryOpaqueId = Base64Url.encode(SyncCrypto.sha256(ByteArray(16)))
    private val libraryId = ByteArray(16) { 8 }
    private val epochKey = ByteArray(32) { 13 }

    private fun registry(generation: Int = 1): WireRegistry {
        val nameKey = SyncTrustCodec.deviceNameKey(epochKey, libraryId, 1)
        val deviceId = Base64Url.encode(ByteArray(16) { 1 })
        val (nameNonce, nameCiphertext) =
            SyncTrustCodec.encryptDeviceName(nameKey, deviceId, "書斎の端末")
        return WireRegistry(
            protocolVersion = SYNC_PROTOCOL_VERSION,
            suite = SYNC_SUITE_ID,
            libraryOpaqueId = libraryOpaqueId,
            registryGeneration = generation,
            epoch = 1,
            devices =
                listOf(
                    WireRegistryDevice(
                        deviceId = deviceId,
                        signingPublicKey = Base64Url.encode(senderPublicKey),
                        hpkePublicKey = Base64Url.encode(ByteArray(65) { 2 }),
                        addedAtGeneration = 1,
                        revokedAtGeneration = null,
                        nameNonce = nameNonce,
                        nameCiphertext = nameCiphertext,
                    ),
                ),
        )
    }

    @Test
    fun signedRegistryRoundTripsAndRejectsTamper() {
        val document = registry()
        val signed = SyncTrustCodec.encodeSignedRegistry(document, SyncCrypto.signingKeyId(senderPublicKey), sender::sign)
        val verified =
            SyncTrustCodec.verifySignedRegistry(signed.bytes, 1, libraryOpaqueId) { keyId ->
                senderPublicKey.takeIf { keyId == senderKeyId }
            }
        assertNotNull(verified)
        assertArrayEquals(signed.hash, requireNotNull(verified).hash)
        // 端末名は平文でwireへ載らず、epoch name keyで復号できる。
        val rawText = signed.bytes.toString(Charsets.UTF_8)
        org.junit.Assert.assertFalse(rawText.contains("書斎の端末"))
        val nameKey = SyncTrustCodec.deviceNameKey(epochKey, libraryId, 1)
        val device = verified.registry.devices.single()
        assertEquals(
            "書斎の端末",
            SyncTrustCodec.decryptDeviceName(nameKey, device.deviceId, device.nameNonce, device.nameCiphertext),
        )
        // 内容改ざん・世代不一致・未知署名鍵を拒否する。
        val tampered =
            signed.bytes
                .toString(Charsets.UTF_8)
                .replace("\"registryGeneration\":1", "\"registryGeneration\":2")
                .toByteArray()
        assertNull(
            SyncTrustCodec.verifySignedRegistry(tampered, 2, libraryOpaqueId) { senderPublicKey },
        )
        assertNull(SyncTrustCodec.verifySignedRegistry(signed.bytes, 2, libraryOpaqueId) { senderPublicKey })
        assertNull(SyncTrustCodec.verifySignedRegistry(signed.bytes, 1, libraryOpaqueId) { null })
    }

    @Test
    fun signedHeadRoundTripsAndRejectsTamper() {
        val head =
            WireHead(
                protocolVersion = SYNC_PROTOCOL_VERSION,
                libraryOpaqueId = libraryOpaqueId,
                generation = "7",
                epoch = 1,
                registryGeneration = 1,
                registryHash = Base64Url.encode(SyncCrypto.sha256(ByteArray(3))),
                deviceLogHeads = mapOf("device" to "object"),
                snapshotObjectId = null,
            )
        val signed = SyncTrustCodec.encodeSignedHead(head, SyncCrypto.signingKeyId(senderPublicKey), sender::sign)
        val verified =
            SyncTrustCodec.verifySignedHead(signed.bytes, libraryOpaqueId) { keyId ->
                senderPublicKey.takeIf { keyId == senderKeyId }
            }
        assertNotNull(verified)
        assertEquals(7L, SyncTrustCodec.toDomainHead(requireNotNull(verified).head).generation)
        val tampered =
            signed.bytes
                .toString(Charsets.UTF_8)
                .replace("\"generation\":\"7\"", "\"generation\":\"8\"")
                .toByteArray()
        assertNull(SyncTrustCodec.verifySignedHead(tampered, libraryOpaqueId) { senderPublicKey })
    }

    @Test
    fun joinRequestMacBindsInviteSecretAndNonce() {
        val secret = ByteArray(32) { 6 }
        val request =
            WireJoinRequest(
                protocolVersion = SYNC_PROTOCOL_VERSION,
                suite = SYNC_SUITE_ID,
                libraryOpaqueId = libraryOpaqueId,
                deviceId = Base64Url.encode(ByteArray(16) { 9 }),
                signingPublicKey = Base64Url.encode(senderPublicKey),
                hpkePublicKey = Base64Url.encode(ByteArray(65) { 3 }),
                inviteNonce = "nonce-1",
                nameNonce = "",
                nameCiphertext = "",
                createdAt = "2026-07-30T00:00:00Z",
            )
        val bytes = SyncTrustCodec.encodeJoinRequest(request, secret)
        assertNotNull(SyncTrustCodec.verifyJoinRequest(bytes, secret, "nonce-1", libraryOpaqueId))
        assertNull(SyncTrustCodec.verifyJoinRequest(bytes, ByteArray(32) { 7 }, "nonce-1", libraryOpaqueId))
        assertNull(SyncTrustCodec.verifyJoinRequest(bytes, secret, "nonce-2", libraryOpaqueId))
        val tampered =
            bytes
                .toString(Charsets.UTF_8)
                .replace(request.hpkePublicKey, Base64Url.encode(ByteArray(65) { 5 }))
                .toByteArray()
        assertNull(SyncTrustCodec.verifyJoinRequest(tampered, secret, "nonce-1", libraryOpaqueId))
    }

    @Test
    fun epochKeyEnvelopeRoundTripsAndEnforcesRecipientExpiryAndInviteMac() {
        val recipient = SyncHpke.generateRecipientKeyPair()
        val recipientDeviceId = Base64Url.encode(ByteArray(16) { 4 })
        val inviteSecret = ByteArray(32) { 15 }
        val authorization =
            WireKeyAuthorization(
                protocolVersion = SYNC_PROTOCOL_VERSION,
                suite = SYNC_SUITE_ID,
                libraryOpaqueId = libraryOpaqueId,
                epoch = 2,
                registryGeneration = 2,
                registryHash = Base64Url.encode(SyncCrypto.sha256(ByteArray(1))),
                trustedHeadHash = Base64Url.encode(SyncCrypto.sha256(ByteArray(2))),
                senderSigningKeyId = senderKeyId,
                recipientDeviceId = recipientDeviceId,
                recipientHpkePublicKey = Base64Url.encode(recipient.publicKey),
                expiresAt = "2026-08-01T00:00:00Z",
                inviteNonce = "invite-nonce",
            )
        val bytes =
            SyncTrustCodec.sealEpochKeyEnvelope(
                authorization = authorization,
                recipientHpkePublicKey = recipient.publicKey,
                epochKey = epochKey,
                sign = sender::sign,
                inviteSecret = inviteSecret,
            )
        val senderKeyFor = { keyId: String -> senderPublicKey.takeIf { keyId == senderKeyId } }
        val nowMillis = 1_753_900_000_000 // 2026-07-30ごろ
        val opened =
            SyncTrustCodec.openEpochKeyEnvelope(
                bytes = bytes,
                recipientDeviceId = recipientDeviceId,
                recipientHpkePrivateKey = recipient.privateKey,
                recipientHpkePublicKey = recipient.publicKey,
                nowMillis = nowMillis,
                senderKeyFor = senderKeyFor,
                requiredInviteSecret = inviteSecret,
            )
        assertNotNull(opened)
        assertArrayEquals(epochKey, requireNotNull(opened).epochKey)
        assertEquals(2, opened.authorization.epoch)

        // 宛先違い・期限切れ・invite secret不一致・署名者未知を拒否する。
        assertNull(
            SyncTrustCodec.openEpochKeyEnvelope(
                bytes,
                Base64Url.encode(ByteArray(16) { 9 }),
                recipient.privateKey,
                recipient.publicKey,
                nowMillis,
                senderKeyFor,
                inviteSecret,
            ),
        )
        assertNull(
            SyncTrustCodec.openEpochKeyEnvelope(
                bytes,
                recipientDeviceId,
                recipient.privateKey,
                recipient.publicKey,
                nowMillis = 4_102_444_800_000, // 2100年
                senderKeyFor = senderKeyFor,
                requiredInviteSecret = inviteSecret,
            ),
        )
        assertNull(
            SyncTrustCodec.openEpochKeyEnvelope(
                bytes,
                recipientDeviceId,
                recipient.privateKey,
                recipient.publicKey,
                nowMillis,
                senderKeyFor,
                requiredInviteSecret = ByteArray(32) { 1 },
            ),
        )
        assertNull(
            SyncTrustCodec.openEpochKeyEnvelope(
                bytes,
                recipientDeviceId,
                recipient.privateKey,
                recipient.publicKey,
                nowMillis,
                senderKeyFor = { null },
                requiredInviteSecret = inviteSecret,
            ),
        )
        // AAD（authorization）改ざんでHPKE openが失敗する。
        val tampered =
            bytes
                .toString(Charsets.UTF_8)
                .replace("\"epoch\":2", "\"epoch\":3")
                .toByteArray()
        assertNull(
            SyncTrustCodec.openEpochKeyEnvelope(
                tampered,
                recipientDeviceId,
                recipient.privateKey,
                recipient.publicKey,
                nowMillis,
                senderKeyFor,
                inviteSecret,
            ),
        )
    }

    @Test
    fun libraryIdEncryptionRoundTripsUnderInviteSecret() {
        val secret = ByteArray(32) { 21 }
        val nonce = ByteArray(16) { 22 }
        val key = SyncTrustCodec.libraryIdKey(secret, nonce)
        val (idNonce, idCiphertext) = SyncTrustCodec.encryptLibraryId(key, "recipient", libraryId)
        assertArrayEquals(
            libraryId,
            SyncTrustCodec.decryptLibraryId(key, "recipient", idNonce, idCiphertext),
        )
        assertNull(SyncTrustCodec.decryptLibraryId(key, "other", idNonce, idCiphertext))
        val wrongKey = SyncTrustCodec.libraryIdKey(ByteArray(32) { 23 }, nonce)
        assertNull(SyncTrustCodec.decryptLibraryId(wrongKey, "recipient", idNonce, idCiphertext))
    }
}
