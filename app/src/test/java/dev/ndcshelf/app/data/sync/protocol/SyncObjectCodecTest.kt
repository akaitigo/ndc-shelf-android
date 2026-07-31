package dev.ndcshelf.app.data.sync.protocol

import dev.ndcshelf.app.data.sync.crypto.Base64Url
import dev.ndcshelf.app.data.sync.crypto.FakeSyncKeyManager
import dev.ndcshelf.app.data.sync.crypto.SyncCrypto
import dev.ndcshelf.app.domain.sync.SyncDeviceRegistry
import dev.ndcshelf.app.domain.sync.SyncRegistryDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncObjectCodecTest {
    private val keyManager = FakeSyncKeyManager()
    private val signingPublicKey = keyManager.ensureDeviceKeys()
    private val epochKey = ByteArray(32) { 11 }
    private val libraryId = ByteArray(16) { 5 }
    private val libraryOpaqueId = Base64Url.encode(SyncCrypto.sha256(libraryId))
    private val deviceIdBytes = ByteArray(16) { 0x21 }
    private val deviceId = Base64Url.encode(deviceIdBytes)
    private val subkey = SyncCrypto.deriveContentSubkey(epochKey, libraryId, 1, deviceIdBytes)

    private val registry =
        SyncDeviceRegistry(
            libraryOpaqueId = libraryOpaqueId,
            registryGeneration = 3,
            epoch = 1,
            devices =
                listOf(
                    SyncRegistryDevice(
                        deviceId = deviceId,
                        name = "端末A",
                        signingPublicKey = Base64Url.encode(signingPublicKey),
                        hpkePublicKey = Base64Url.encode(ByteArray(65) { 4 }),
                        addedAtGeneration = 1,
                        revokedAtGeneration = null,
                    ),
                ),
        )

    private fun payloadBytes(counter: Long = 1): ByteArray =
        SyncWireJson.canonicalEncode(
            WireOperationsPayload(
                previousObjectHash = null,
                deviceId = deviceId,
                counterRange = null,
                versionVector = mapOf(deviceId to counter.toString()),
                transactions = emptyList(),
                createdAt = "2026-07-30T00:00:00Z",
            ),
        )

    private fun seal(
        counter: Long = 1,
        registryGeneration: Int = 1,
    ): SyncObjectCodec.SealedObject =
        SyncObjectCodec.seal(
            payloadCanonical = payloadBytes(counter),
            libraryOpaqueId = libraryOpaqueId,
            epoch = 1,
            registryGeneration = registryGeneration,
            contentSubkey = subkey,
            encryptionCounter = counter,
            signingKeyId = SyncCrypto.signingKeyId(signingPublicKey),
            sign = keyManager::sign,
        )

    private fun open(
        bytes: ByteArray,
        expectedObjectId: String,
    ): SyncObjectCodec.OpenResult =
        SyncObjectCodec.open(
            bytes = bytes,
            expectedObjectId = expectedObjectId,
            libraryOpaqueId = libraryOpaqueId,
            libraryId = libraryId,
            registry = registry,
            maxObjectSizeBytes = 16L * 1024 * 1024,
            epochKeyProvider = { epoch -> epochKey.takeIf { epoch == 1 } },
        )

    @Test
    fun sealedObjectRoundTripsThroughFullVerification() {
        val sealed = seal()
        val result = open(sealed.bytes, sealed.objectId)
        assertTrue(describe(result), result is SyncObjectCodec.OpenResult.Valid)
        val valid = result as SyncObjectCodec.OpenResult.Valid
        assertEquals(deviceId, valid.sender.deviceId)
        assertTrue(valid.payload is WirePayload.Operations)
        // paddingにより64 KiB単位へ揃う。
        assertTrue(sealed.bytes.size > 64 * 1024)
    }

    @Test
    fun tamperedCiphertextHeaderSignatureOrAddressIsRejected() {
        val sealed = seal()
        val rawText = sealed.bytes.toString(Charsets.UTF_8)

        // ciphertext改ざん: objectId不一致として拒否する。
        val envelope = SyncWireJson.strict.decodeFromString(WireSyncEnvelope.serializer(), rawText)
        val tamperedCiphertext =
            envelope.copy(
                ciphertext =
                    Base64Url.encode(
                        Base64Url.decodeOrThrow(envelope.ciphertext).also {
                            it[100] = (it[100].toInt() xor 1).toByte()
                        },
                    ),
            )
        assertInvalid(tamperedCiphertext)

        // header改ざん（epoch差し替え）。
        assertInvalid(envelope.copy(protectedHeader = envelope.protectedHeader.copy(epoch = 2)))

        // 署名改ざん。
        assertInvalid(
            envelope.copy(
                signature =
                    Base64Url.encode(
                        Base64Url.decodeOrThrow(envelope.signature).also {
                            it[5] = (it[5].toInt() xor 1).toByte()
                        },
                    ),
            ),
        )

        // 別addressからの取得（content-addressed検証）。
        val wrongAddress = open(sealed.bytes, Base64Url.encode(SyncCrypto.sha256(ByteArray(1))))
        assertTrue(wrongAddress is SyncObjectCodec.OpenResult.Invalid)
    }

    @Test
    fun unknownSuiteProtocolLibraryOrOversizeIsRejected() {
        val sealed = seal()
        val envelope =
            SyncWireJson.strict.decodeFromString(
                WireSyncEnvelope.serializer(),
                sealed.bytes.toString(Charsets.UTF_8),
            )
        assertInvalid(envelope.copy(protectedHeader = envelope.protectedHeader.copy(suite = "OTHER")))
        assertInvalid(
            envelope.copy(protectedHeader = envelope.protectedHeader.copy(protocolVersion = "2.0")),
        )
        assertInvalid(
            envelope.copy(protectedHeader = envelope.protectedHeader.copy(libraryOpaqueId = "other")),
        )
        val oversize =
            SyncObjectCodec.open(
                bytes = sealed.bytes,
                expectedObjectId = sealed.objectId,
                libraryOpaqueId = libraryOpaqueId,
                libraryId = libraryId,
                registry = registry,
                maxObjectSizeBytes = 1_000,
                epochKeyProvider = { epochKey },
            )
        assertTrue(oversize is SyncObjectCodec.OpenResult.Invalid)
    }

    @Test
    fun revokedDeviceObjectsAtOrAfterRevocationGenerationAreRejected() {
        val revokedRegistry =
            registry.copy(
                registryGeneration = 5,
                devices = registry.devices.map { it.copy(revokedAtGeneration = 4) },
            )
        // 失効前generationのobjectは受理する。
        val preRevocation = seal(counter = 1, registryGeneration = 3)
        val accepted =
            SyncObjectCodec.open(
                bytes = preRevocation.bytes,
                expectedObjectId = preRevocation.objectId,
                libraryOpaqueId = libraryOpaqueId,
                libraryId = libraryId,
                registry = revokedRegistry,
                maxObjectSizeBytes = 16L * 1024 * 1024,
                epochKeyProvider = { epochKey },
            )
        assertTrue(accepted is SyncObjectCodec.OpenResult.Valid)
        // 失効generation以後のobjectは拒否する。
        val postRevocation = seal(counter = 2, registryGeneration = 4)
        val rejected =
            SyncObjectCodec.open(
                bytes = postRevocation.bytes,
                expectedObjectId = postRevocation.objectId,
                libraryOpaqueId = libraryOpaqueId,
                libraryId = libraryId,
                registry = revokedRegistry,
                maxObjectSizeBytes = 16L * 1024 * 1024,
                epochKeyProvider = { epochKey },
            )
        assertTrue(rejected is SyncObjectCodec.OpenResult.Invalid)
    }

    @Test
    fun unknownSigningKeyOrMissingEpochKeyIsRejected() {
        val sealed = seal()
        val strangerRegistry =
            registry.copy(
                devices =
                    registry.devices.map {
                        it.copy(signingPublicKey = Base64Url.encode(FakeSyncKeyManager().ensureDeviceKeys()))
                    },
            )
        val unknownKey =
            SyncObjectCodec.open(
                bytes = sealed.bytes,
                expectedObjectId = sealed.objectId,
                libraryOpaqueId = libraryOpaqueId,
                libraryId = libraryId,
                registry = strangerRegistry,
                maxObjectSizeBytes = 16L * 1024 * 1024,
                epochKeyProvider = { epochKey },
            )
        assertTrue(unknownKey is SyncObjectCodec.OpenResult.Invalid)
        val missingEpochKey =
            SyncObjectCodec.open(
                bytes = sealed.bytes,
                expectedObjectId = sealed.objectId,
                libraryOpaqueId = libraryOpaqueId,
                libraryId = libraryId,
                registry = registry,
                maxObjectSizeBytes = 16L * 1024 * 1024,
                epochKeyProvider = { null },
            )
        assertTrue(missingEpochKey is SyncObjectCodec.OpenResult.Invalid)
    }

    private fun describe(result: SyncObjectCodec.OpenResult): String =
        (result as? SyncObjectCodec.OpenResult.Invalid)?.reason ?: "valid"

    private fun assertInvalid(envelope: WireSyncEnvelope) {
        val bytes =
            SyncWireJson.strict
                .encodeToString(WireSyncEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
        val result = open(bytes, envelope.objectId)
        assertTrue(result is SyncObjectCodec.OpenResult.Invalid)
    }
}
