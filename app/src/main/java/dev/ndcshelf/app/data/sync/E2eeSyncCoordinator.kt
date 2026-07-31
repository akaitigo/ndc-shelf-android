package dev.ndcshelf.app.data.sync

import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.SyncIdentityEntity
import dev.ndcshelf.app.data.local.SyncInviteEntity
import dev.ndcshelf.app.data.local.SyncPeerDeviceEntity
import dev.ndcshelf.app.data.local.SyncProcessedEnvelopeEntity
import dev.ndcshelf.app.data.local.SyncQuarantineEntity
import dev.ndcshelf.app.data.local.SyncWrappedKeyEntity
import dev.ndcshelf.app.data.sync.backend.SyncBackendFactory
import dev.ndcshelf.app.data.sync.crypto.Base64Url
import dev.ndcshelf.app.data.sync.crypto.SyncCrypto
import dev.ndcshelf.app.data.sync.crypto.SyncHpke
import dev.ndcshelf.app.data.sync.crypto.SyncKeyManager
import dev.ndcshelf.app.data.sync.crypto.SyncKeyUnavailableException
import dev.ndcshelf.app.data.sync.crypto.WrappedKeyBlob
import dev.ndcshelf.app.data.sync.protocol.SyncObjectCodec
import dev.ndcshelf.app.data.sync.protocol.SyncTrustCodec
import dev.ndcshelf.app.data.sync.protocol.SyncWireJson
import dev.ndcshelf.app.data.sync.protocol.SyncWireTime
import dev.ndcshelf.app.data.sync.protocol.WireHead
import dev.ndcshelf.app.data.sync.protocol.WireJoinRequest
import dev.ndcshelf.app.data.sync.protocol.WireKeyAuthorization
import dev.ndcshelf.app.data.sync.protocol.WirePayload
import dev.ndcshelf.app.data.sync.protocol.WireRegistry
import dev.ndcshelf.app.data.sync.protocol.WireRegistryDevice
import dev.ndcshelf.app.data.sync.protocol.WireSignedHead
import dev.ndcshelf.app.data.sync.protocol.WireSignedRegistry
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.sync.LibrarySyncScheduler
import dev.ndcshelf.app.domain.sync.MAX_SYNC_BATCH_OPERATIONS
import dev.ndcshelf.app.domain.sync.MAX_SYNC_DEVICES
import dev.ndcshelf.app.domain.sync.SYNC_PROTOCOL_MAJOR
import dev.ndcshelf.app.domain.sync.SYNC_PROTOCOL_VERSION
import dev.ndcshelf.app.domain.sync.SYNC_SUITE_ID
import dev.ndcshelf.app.domain.sync.SyncActionResult
import dev.ndcshelf.app.domain.sync.SyncBackend
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncBackendException
import dev.ndcshelf.app.domain.sync.SyncCasResult
import dev.ndcshelf.app.domain.sync.SyncConfigurationStatus
import dev.ndcshelf.app.domain.sync.SyncDeletionReceipt
import dev.ndcshelf.app.domain.sync.SyncDeviceInfo
import dev.ndcshelf.app.domain.sync.SyncDeviceRegistry
import dev.ndcshelf.app.domain.sync.SyncFailure
import dev.ndcshelf.app.domain.sync.SyncFailureReason
import dev.ndcshelf.app.domain.sync.SyncInvite
import dev.ndcshelf.app.domain.sync.SyncJoinCandidate
import dev.ndcshelf.app.domain.sync.SyncOperation
import dev.ndcshelf.app.domain.sync.SyncSnapshotData
import dev.ndcshelf.app.domain.sync.SyncVersionVector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * E2EE同期のlifecycle coordinator（Issue #38）。SyncBackend契約だけへ依存し、
 * 有効化・genesis/bootstrap snapshot・手動同期・端末追加/承認/失効・
 * sign-out・remote全削除を提供する。全公開操作は同意（LIBRARY_SYNC）を
 * fail-closedで検査し、同期OFFではbackend・鍵・fileへ一切触れない。
 */
class E2eeSyncCoordinator(
    private val database: AppDatabase,
    private val engine: RoomSyncEngine,
    private val keyManager: SyncKeyManager,
    private val backendFactory: SyncBackendFactory,
    private val consentRepository: ConsentRepository,
    private val scheduler: LibrarySyncScheduler,
    private val genesisSource: SyncGenesisSource = SyncGenesisSource(database),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: suspend (attempt: Int) -> Unit = { attempt ->
        delay((500L shl attempt) + Random.nextLong(250))
    },
) {
    private val keyDao = database.syncKeyDao()

    // ---- Observation ----

    fun observeConfiguration(): Flow<SyncConfigurationStatus> =
        keyDao.observeIdentity().map { identity ->
            if (identity == null) {
                SyncConfigurationStatus()
            } else {
                SyncConfigurationStatus(
                    configured = true,
                    activated = identity.activated,
                    joinPending = !identity.activated,
                    backendType = identity.backendType,
                    deviceName = identity.deviceName,
                    epoch = identity.epoch,
                    hardwareBackedKeys = identity.hardwareBackedKeys,
                    securityLockout = identity.securityLockout,
                )
            }
        }

    fun observeDevices(): Flow<List<SyncDeviceInfo>> =
        keyDao.observePeerDevices().map { peers ->
            peers.map { peer ->
                SyncDeviceInfo(
                    deviceId = peer.deviceId,
                    name = peer.name,
                    isSelf = peer.isSelf,
                    revoked = peer.revokedAtGeneration != null,
                    lastSyncAtMillis = peer.lastSyncAt,
                    addedAtGeneration = peer.addedAtGeneration,
                )
            }
        }

    // ---- Enable: create a new library ----

    suspend fun createLibrary(
        deviceName: String,
        backendType: String,
        backendConfig: String,
    ): SyncActionResult {
        consentFailure()?.let { return it }
        if (keyDao.getIdentity() != null) return failure(SyncFailureReason.ALREADY_ENABLED)
        return try {
            if (backendFactory.discoverLibrary(backendType, backendConfig) != null) {
                return failure(SyncFailureReason.LIBRARY_ALREADY_EXISTS)
            }
            val identity = provisionIdentity(deviceName, backendType, backendConfig)
            try {
                activateNewLibrary(identity)
                scheduler.reconcile(true)
                SyncActionResult.Success()
            } catch (error: Exception) {
                // 8.1節: 失敗時はremote partial stateを削除し、local dataを変更しない。
                runCatching {
                    backendFactory
                        .create(backendType, backendConfig, identity.libraryOpaqueId)
                        .requestRemoteDeletion()
                }
                abandonSyncState(requiresReregistration = false)
                throw error
            }
        } catch (error: SyncBackendException) {
            failure(SyncFailureReason.BACKEND, error.kind)
        } catch (_: SyncKeyUnavailableException) {
            failure(SyncFailureReason.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            failure(SyncFailureReason.INTERNAL)
        }
    }

    private suspend fun provisionIdentity(
        deviceName: String,
        backendType: String,
        backendConfig: String,
    ): SyncIdentityEntity {
        val signingPublicKey = keyManager.ensureDeviceKeys()
        val libraryIdBytes = SyncCrypto.randomBytes(SyncCrypto.LIBRARY_ID_BYTES)
        val backendSalt = SyncCrypto.randomBytes(32)
        val libraryId = Base64Url.encode(libraryIdBytes)
        val libraryOpaqueId = Base64Url.encode(SyncCrypto.sha256(libraryIdBytes + backendSalt))
        val deviceId = Base64Url.encode(SyncCrypto.randomBytes(SyncCrypto.DEVICE_ID_BYTES))
        val hpkeKeyPair = SyncHpke.generateRecipientKeyPair()
        val epochKey = SyncCrypto.randomBytes(SyncCrypto.EPOCH_KEY_BYTES)
        val now = nowMillis()
        val identity =
            SyncIdentityEntity(
                libraryId = libraryId,
                libraryOpaqueId = libraryOpaqueId,
                deviceId = deviceId,
                deviceName = deviceName.take(SyncTrustCodec.MAX_DEVICE_NAME_LENGTH).ifBlank { "Android" },
                epoch = 1,
                registryGeneration = 1,
                registryHash = null,
                headGeneration = 0,
                trustedHeadHash = null,
                encryptionCounter = 0,
                lastUploadedObjectId = null,
                backendType = backendType,
                backendConfig = backendConfig,
                hardwareBackedKeys = keyManager.isHardwareBacked(),
                activated = false,
                securityLockout = null,
                createdAt = now,
            )
        database.withTransaction {
            keyDao.upsertIdentity(identity)
            storeWrappedKey(SyncKeyManager.KEY_TYPE_HPKE_PRIVATE, 1, hpkeKeyPair.privateKey, libraryOpaqueId)
            storeWrappedKey(SyncKeyManager.KEY_TYPE_EPOCH, 1, epochKey, libraryOpaqueId)
            keyDao.upsertPeerDevices(
                listOf(
                    SyncPeerDeviceEntity(
                        deviceId = deviceId,
                        name = identity.deviceName,
                        signingPublicKey = Base64Url.encode(signingPublicKey),
                        hpkePublicKey = Base64Url.encode(hpkeKeyPair.publicKey),
                        addedAtGeneration = 1,
                        revokedAtGeneration = null,
                        lastSyncAt = now,
                        lastObjectId = null,
                        lastEncryptionCounter = 0,
                        isSelf = true,
                    ),
                ),
            )
        }
        hpkeKeyPair.privateKey.fill(0)
        epochKey.fill(0)
        return identity
    }

    private suspend fun activateNewLibrary(identity: SyncIdentityEntity) {
        engine.initializeDevice(identity.deviceId)
        val genesis = genesisSource.collect()
        if (genesis.isNotEmpty()) engine.record(genesis)
        val backend =
            backendFactory.create(identity.backendType, identity.backendConfig, identity.libraryOpaqueId)
        checkCapabilities(backend)
        val self = requireNotNull(keyDao.findPeerDevice(identity.deviceId))
        val registry = buildRegistry(identity, listOf(self), registryGeneration = 1, epoch = 1)
        val signedRegistry =
            SyncTrustCodec.encodeSignedRegistry(registry, signingKeyId(), keyManager::sign)
        uploadPendingOperations(backend)
        val snapshotObjectId = uploadSnapshot(backend, requireIdentity())
        val afterUpload = requireIdentity()
        val head =
            WireHead(
                protocolVersion = SYNC_PROTOCOL_VERSION,
                libraryOpaqueId = identity.libraryOpaqueId,
                generation = "1",
                epoch = 1,
                registryGeneration = 1,
                registryHash = Base64Url.encode(signedRegistry.hash),
                deviceLogHeads =
                    afterUpload.lastUploadedObjectId
                        ?.let { mapOf(identity.deviceId to it) }
                        .orEmpty(),
                snapshotObjectId = snapshotObjectId,
            )
        val signedHead = SyncTrustCodec.encodeSignedHead(head, signingKeyId(), keyManager::sign)
        backend.createLibrary(signedRegistry.bytes, signedHead.bytes)
        database.withTransaction {
            keyDao.upsertIdentity(
                requireIdentity().copy(
                    activated = true,
                    registryHash = Base64Url.encode(signedRegistry.hash),
                    headGeneration = 1,
                    trustedHeadHash = Base64Url.encode(signedHead.hash),
                ),
            )
        }
        engine.markSyncSucceeded()
    }

    // ---- Enable: join an existing library ----

    suspend fun joinLibrary(
        deviceName: String,
        backendType: String,
        backendConfig: String,
        inviteCode: String,
    ): SyncActionResult {
        consentFailure()?.let { return it }
        if (keyDao.getIdentity() != null) return failure(SyncFailureReason.ALREADY_ENABLED)
        val invite = SyncInvite.decode(inviteCode) ?: return failure(SyncFailureReason.INVITE_INVALID)
        val (inviteNonce, inviteSecret) = invite
        if (Base64Url.decode(inviteNonce) == null || Base64Url.decode(inviteSecret) == null) {
            return failure(SyncFailureReason.INVITE_INVALID)
        }
        return try {
            val libraryOpaqueId =
                backendFactory.discoverLibrary(backendType, backendConfig)
                    ?: return failure(SyncFailureReason.LIBRARY_NOT_FOUND)
            val signingPublicKey = keyManager.ensureDeviceKeys()
            val libraryId = ""
            val deviceId = Base64Url.encode(SyncCrypto.randomBytes(SyncCrypto.DEVICE_ID_BYTES))
            val hpkeKeyPair = SyncHpke.generateRecipientKeyPair()
            val now = nowMillis()
            val identity =
                SyncIdentityEntity(
                    libraryId = libraryId,
                    libraryOpaqueId = libraryOpaqueId,
                    deviceId = deviceId,
                    deviceName =
                        deviceName.take(SyncTrustCodec.MAX_DEVICE_NAME_LENGTH).ifBlank { "Android" },
                    epoch = 0,
                    registryGeneration = 0,
                    registryHash = null,
                    headGeneration = 0,
                    trustedHeadHash = null,
                    encryptionCounter = 0,
                    lastUploadedObjectId = null,
                    backendType = backendType,
                    backendConfig = backendConfig,
                    hardwareBackedKeys = keyManager.isHardwareBacked(),
                    activated = false,
                    securityLockout = null,
                    createdAt = now,
                )
            database.withTransaction {
                keyDao.upsertIdentity(identity)
                storeWrappedKey(SyncKeyManager.KEY_TYPE_HPKE_PRIVATE, 1, hpkeKeyPair.privateKey, libraryOpaqueId)
                keyDao.insertInvite(
                    SyncInviteEntity(
                        nonce = inviteNonce,
                        secret = inviteSecret,
                        createdAt = now,
                        expiresAt = now + SyncInvite.VALIDITY_MILLIS,
                        consumedAt = null,
                    ),
                )
            }
            val nameKey =
                SyncTrustCodec.joinNameKey(
                    Base64Url.decodeOrThrow(inviteSecret),
                    Base64Url.decodeOrThrow(inviteNonce),
                )
            val (nameNonce, nameCiphertext) =
                SyncTrustCodec.encryptDeviceName(nameKey, deviceId, identity.deviceName)
            val request =
                WireJoinRequest(
                    protocolVersion = SYNC_PROTOCOL_VERSION,
                    suite = SYNC_SUITE_ID,
                    libraryOpaqueId = libraryOpaqueId,
                    deviceId = deviceId,
                    signingPublicKey = Base64Url.encode(signingPublicKey),
                    hpkePublicKey = Base64Url.encode(hpkeKeyPair.publicKey),
                    inviteNonce = inviteNonce,
                    nameNonce = nameNonce,
                    nameCiphertext = nameCiphertext,
                    createdAt = SyncWireTime.encode(now),
                )
            hpkeKeyPair.privateKey.fill(0)
            val requestBytes =
                SyncTrustCodec.encodeJoinRequest(request, Base64Url.decodeOrThrow(inviteSecret))
            val backend = backendFactory.create(backendType, backendConfig, libraryOpaqueId)
            checkCapabilities(backend)
            backend.putJoinRequest(deviceId, requestBytes)
            val canonicalRequest =
                SyncWireJson.canonicalEncode(request)
            SyncActionResult.JoinPending(
                SyncCrypto.verificationCode(Base64Url.decodeOrThrow(inviteNonce), canonicalRequest),
            )
        } catch (error: SyncBackendException) {
            abandonSyncState(requiresReregistration = false)
            failure(SyncFailureReason.BACKEND, error.kind)
        } catch (_: SyncKeyUnavailableException) {
            abandonSyncState(requiresReregistration = false)
            failure(SyncFailureReason.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            abandonSyncState(requiresReregistration = false)
            failure(SyncFailureReason.INTERNAL)
        }
    }

    /** 承認待ちのjoinを完了する。envelope未着ならJOIN_NOT_READYを返す。 */
    suspend fun completeJoin(): SyncActionResult {
        consentFailure()?.let { return it }
        val identity = keyDao.getIdentity() ?: return failure(SyncFailureReason.NOT_ENABLED)
        if (identity.activated) return failure(SyncFailureReason.ALREADY_ENABLED)
        keyDao.deleteExpiredInvites(nowMillis())
        val invite =
            keyDao.findUnconsumedInvites(nowMillis()).firstOrNull()
                ?: return failure(SyncFailureReason.INVITE_INVALID)
        return try {
            val backend =
                backendFactory.create(identity.backendType, identity.backendConfig, identity.libraryOpaqueId)
            completeJoinWithBackend(backend, identity, invite)
        } catch (error: SyncBackendException) {
            failure(SyncFailureReason.BACKEND, error.kind)
        } catch (_: SyncKeyUnavailableException) {
            failure(SyncFailureReason.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            failure(SyncFailureReason.INTERNAL)
        }
    }

    private suspend fun completeJoinWithBackend(
        backend: SyncBackend,
        identity: SyncIdentityEntity,
        invite: SyncInviteEntity,
    ): SyncActionResult {
        val inviteSecret = Base64Url.decodeOrThrow(invite.secret)
        val hpkePrivateKey = unwrapKey(SyncKeyManager.KEY_TYPE_HPKE_PRIVATE, 1, identity.libraryOpaqueId)
        val selfHpkePublicKey = hpkePublicKeyOfSelf(identity)
        val envelopes = backend.listDeviceEnvelopes()
        for (stored in envelopes) {
            val parsed =
                runCatching {
                    SyncWireJson.lenient.decodeFromString(
                        dev.ndcshelf.app.data.sync.protocol.WireKeyEnvelope
                            .serializer(),
                        stored.bytes.toString(Charsets.UTF_8),
                    )
                }.getOrNull() ?: continue
            if (parsed.authorization.recipientDeviceId != identity.deviceId) continue
            if (parsed.authorization.inviteNonce != invite.nonce) continue
            val registryBytes =
                runCatching { backend.getRegistry(parsed.authorization.registryGeneration) }
                    .getOrNull() ?: continue
            val verifiedRegistry =
                verifyRegistryForJoin(registryBytes, parsed.authorization, identity) ?: continue
            val senderKeys =
                verifiedRegistry.registry.devices
                    .filter { it.revokedAtGeneration == null }
                    .associateBy { device ->
                        Base64Url.decode(device.signingPublicKey)?.let {
                            Base64Url.encode(SyncCrypto.signingKeyId(it))
                        } ?: ""
                    }
            val opened =
                SyncTrustCodec.openEpochKeyEnvelope(
                    bytes = stored.bytes,
                    recipientDeviceId = identity.deviceId,
                    recipientHpkePrivateKey = hpkePrivateKey,
                    recipientHpkePublicKey = selfHpkePublicKey,
                    nowMillis = nowMillis(),
                    senderKeyFor = { keyId ->
                        senderKeys[keyId]?.let { Base64Url.decode(it.signingPublicKey) }
                    },
                    requiredInviteSecret = inviteSecret,
                ) ?: continue
            // libraryId（HKDF salt）は招待secret由来keyで復号する。欠落は不正envelope。
            val libraryIdKey =
                SyncTrustCodec.libraryIdKey(inviteSecret, Base64Url.decodeOrThrow(invite.nonce))
            val libraryIdBytes =
                opened.authorization.libraryIdNonce?.let { nonce ->
                    opened.authorization.libraryIdCiphertext?.let { ciphertext ->
                        SyncTrustCodec.decryptLibraryId(libraryIdKey, identity.deviceId, nonce, ciphertext)
                    }
                } ?: continue
            hpkePrivateKey.fill(0)
            return finishJoin(backend, identity, invite, opened, verifiedRegistry.registry, libraryIdBytes)
        }
        hpkePrivateKey.fill(0)
        return failure(SyncFailureReason.JOIN_NOT_READY)
    }

    private fun verifyRegistryForJoin(
        registryBytes: ByteArray,
        authorization: WireKeyAuthorization,
        identity: SyncIdentityEntity,
    ): SyncTrustCodec.VerifiedRegistry? {
        // joinのtrust anchorは招待secretで束縛されたauthorizationのregistryHash。
        val verified =
            SyncTrustCodec.verifySignedRegistry(
                bytes = registryBytes,
                expectedGeneration = authorization.registryGeneration,
                expectedLibraryOpaqueId = identity.libraryOpaqueId,
                authorizedBy = { keyId ->
                    decodeSignedRegistryDeviceKey(registryBytes, keyId)
                },
            ) ?: return null
        if (Base64Url.encode(verified.hash) != authorization.registryHash) return null
        val self = verified.registry.devices.firstOrNull { it.deviceId == identity.deviceId } ?: return null
        if (self.revokedAtGeneration != null) return null
        return verified
    }

    /** registry文書内の（自己申告の）鍵で署名検証する（join時のself-consistency確認）。 */
    private fun decodeSignedRegistryDeviceKey(
        registryBytes: ByteArray,
        signedByKeyId: String,
    ): ByteArray? {
        val signed =
            runCatching {
                SyncWireJson.lenient.decodeFromString(
                    WireSignedRegistry.serializer(),
                    registryBytes.toString(Charsets.UTF_8),
                )
            }.getOrNull() ?: return null
        return signed.registry.devices
            .asSequence()
            .filter { it.revokedAtGeneration == null }
            .mapNotNull { Base64Url.decode(it.signingPublicKey) }
            .firstOrNull { der -> Base64Url.encode(SyncCrypto.signingKeyId(der)) == signedByKeyId }
    }

    private suspend fun finishJoin(
        backend: SyncBackend,
        identity: SyncIdentityEntity,
        invite: SyncInviteEntity,
        opened: SyncTrustCodec.OpenedEpochKeyEnvelope,
        registry: WireRegistry,
        libraryIdBytes: ByteArray,
    ): SyncActionResult {
        val authorization = opened.authorization
        val epochKey = opened.epochKey
        val libraryId = Base64Url.encode(libraryIdBytes)
        database.withTransaction {
            storeWrappedKey(
                SyncKeyManager.KEY_TYPE_EPOCH,
                authorization.epoch,
                epochKey,
                identity.libraryOpaqueId,
            )
            keyDao.insertProcessedEnvelope(
                SyncProcessedEnvelopeEntity(opened.envelopeId, authorization.inviteNonce, nowMillis()),
            )
            keyDao.consumeInvite(invite.nonce, nowMillis())
            keyDao.upsertIdentity(
                requireIdentity().copy(
                    libraryId = libraryId,
                    epoch = authorization.epoch,
                    registryGeneration = authorization.registryGeneration,
                    registryHash = authorization.registryHash,
                ),
            )
        }
        updatePeerCache(registry, isSelfId = identity.deviceId)
        // bootstrap snapshotをtrusted headから取り込む。
        val snapshotData =
            authorization.bootstrapSnapshotObjectId?.let { snapshotHead ->
                downloadSnapshotChain(backend, snapshotHead)
            }
        val preexistingIds =
            snapshotData
                ?.fieldStates
                ?.map { it.entityType to it.entityId }
                ?.toSet()
                .orEmpty()
        engine.initializeDevice(identity.deviceId)
        snapshotData?.let { engine.bootstrapFromSnapshot(it) }
        val localOnly = genesisSource.collect(excludeEntityIds = preexistingIds)
        if (localOnly.isNotEmpty()) engine.record(localOnly)
        database.withTransaction {
            keyDao.upsertIdentity(requireIdentity().copy(activated = true))
        }
        runCatching { backend.deleteJoinRequest(identity.deviceId) }
        epochKey.fill(0)
        scheduler.reconcile(true)
        return syncNow()
    }

    private suspend fun downloadSnapshotChain(
        backend: SyncBackend,
        snapshotHeadObjectId: String,
    ): SyncSnapshotData? {
        val identity = requireIdentity()
        val registry = cachedRegistry() ?: return null
        val chain = mutableListOf<WirePayload.Snapshot>()
        var cursor: String? = snapshotHeadObjectId
        var depth = 0
        val epochKeys = loadEpochKeys(identity)
        try {
            while (cursor != null && depth < MAX_CHAIN_DEPTH) {
                val bytes = backend.getObject(cursor)
                val opened =
                    SyncObjectCodec.open(
                        bytes = bytes,
                        expectedObjectId = cursor,
                        libraryOpaqueId = identity.libraryOpaqueId,
                        libraryId = libraryIdBytes(identity),
                        registry = registry,
                        maxObjectSizeBytes = maxObjectBytes,
                        epochKeyProvider = { epoch -> epochKeys[epoch] },
                    )
                when (opened) {
                    is SyncObjectCodec.OpenResult.Invalid -> {
                        quarantineAndLock(cursor, bytes, opened.reason)
                        return null
                    }

                    is SyncObjectCodec.OpenResult.Valid -> {
                        val snapshot = opened.payload as? WirePayload.Snapshot ?: return null
                        chain += snapshot
                        cursor = snapshot.payload.previousObjectHash
                    }
                }
                depth += 1
            }
        } finally {
            epochKeys.values.forEach { it.fill(0) }
        }
        if (chain.isEmpty()) return null
        val fieldStates = chain.asReversed().flatMap { it.payload.fieldStates }
        val tombstones = chain.asReversed().flatMap { it.payload.tombstones }
        val vector = chain.first().payload.versionVector
        val combined =
            dev.ndcshelf.app.data.sync.protocol.WireSnapshotPayload(
                previousObjectHash = null,
                deviceId = chain.first().payload.deviceId,
                versionVector = vector,
                fieldStates = fieldStates,
                tombstones = tombstones,
                createdAt = chain.first().payload.createdAt,
            )
        return SyncWireConverters.fromWireSnapshotPayload(combined)
    }

    // ---- Invite and approval (existing device) ----

    suspend fun createInvite(): SyncActionResult {
        consentFailure()?.let { return it }
        val identity = keyDao.getIdentity() ?: return failure(SyncFailureReason.NOT_ENABLED)
        if (!identity.activated) return failure(SyncFailureReason.JOIN_NOT_READY)
        val now = nowMillis()
        val invite =
            SyncInviteEntity(
                nonce = Base64Url.encode(SyncCrypto.randomBytes(16)),
                secret = Base64Url.encode(SyncCrypto.randomBytes(32)),
                createdAt = now,
                expiresAt = now + SyncInvite.VALIDITY_MILLIS,
                consumedAt = null,
            )
        database.withTransaction {
            keyDao.deleteExpiredInvites(now)
            keyDao.insertInvite(invite)
        }
        lastCreatedInvite = SyncInvite(invite.nonce, invite.secret, invite.expiresAt)
        return SyncActionResult.Success()
    }

    /** 直近に作成した招待コード（UI表示用）。processを跨いで保持しない。 */
    @Volatile
    var lastCreatedInvite: SyncInvite? = null
        private set

    suspend fun pendingJoinRequests(): List<SyncJoinCandidate> {
        if (!consentRepository.isGranted(ConsentPurpose.LIBRARY_SYNC)) return emptyList()
        val identity = keyDao.getIdentity()?.takeIf { it.activated } ?: return emptyList()
        val now = nowMillis()
        keyDao.deleteExpiredInvites(now)
        val invites = keyDao.findUnconsumedInvites(now)
        if (invites.isEmpty()) return emptyList()
        val backend =
            try {
                backendFactory.create(identity.backendType, identity.backendConfig, identity.libraryOpaqueId)
            } catch (_: SyncBackendException) {
                return emptyList()
            }
        val requests =
            try {
                backend.listJoinRequests()
            } catch (_: SyncBackendException) {
                return emptyList()
            }
        return requests.mapNotNull { stored ->
            invites.firstNotNullOfOrNull { invite ->
                val secret = Base64Url.decode(invite.secret) ?: return@firstNotNullOfOrNull null
                val verified =
                    SyncTrustCodec.verifyJoinRequest(
                        bytes = stored.bytes,
                        inviteSecret = secret,
                        expectedNonce = invite.nonce,
                        expectedLibraryOpaqueId = identity.libraryOpaqueId,
                    ) ?: return@firstNotNullOfOrNull null
                val nameKey =
                    SyncTrustCodec.joinNameKey(secret, Base64Url.decodeOrThrow(invite.nonce))
                SyncJoinCandidate(
                    deviceId = verified.request.deviceId,
                    deviceName =
                        SyncTrustCodec.decryptDeviceName(
                            nameKey,
                            verified.request.deviceId,
                            verified.request.nameNonce,
                            verified.request.nameCiphertext,
                        ) ?: "",
                    signingPublicKey = verified.request.signingPublicKey,
                    hpkePublicKey = verified.request.hpkePublicKey,
                    inviteNonce = invite.nonce,
                    verificationCode =
                        SyncCrypto.verificationCode(
                            Base64Url.decodeOrThrow(invite.nonce),
                            verified.canonicalRequest,
                        ),
                )
            }
        }
    }

    /** verification code照合後の明示承認。registry世代を進めHPKEで鍵を引き継ぐ。 */
    suspend fun approveJoin(candidate: SyncJoinCandidate): SyncActionResult {
        consentFailure()?.let { return it }
        val identity = keyDao.getIdentity() ?: return failure(SyncFailureReason.NOT_ENABLED)
        if (!identity.activated) return failure(SyncFailureReason.JOIN_NOT_READY)
        identity.securityLockout?.let { return failure(SyncFailureReason.SECURITY_LOCKOUT) }
        return try {
            val backend =
                backendFactory.create(identity.backendType, identity.backendConfig, identity.libraryOpaqueId)
            approveJoinWithBackend(backend, identity, candidate)
        } catch (error: SyncBackendException) {
            failure(SyncFailureReason.BACKEND, error.kind)
        } catch (_: SyncKeyUnavailableException) {
            failure(SyncFailureReason.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            failure(SyncFailureReason.INTERNAL)
        }
    }

    private suspend fun approveJoinWithBackend(
        backend: SyncBackend,
        identity: SyncIdentityEntity,
        candidate: SyncJoinCandidate,
    ): SyncActionResult {
        val invite =
            keyDao.findInvite(candidate.inviteNonce)?.takeIf {
                it.consumedAt == null && it.expiresAt >= nowMillis()
            } ?: return failure(SyncFailureReason.INVITE_INVALID)
        val verifiedHead = fetchAndVerifyHead(backend, identity) ?: return headFailure()
        val current = requireIdentity()
        val peers = keyDao.getPeerDevices()
        if (peers.count { it.revokedAtGeneration == null } >= MAX_SYNC_DEVICES) {
            return failure(SyncFailureReason.INTERNAL)
        }
        if (peers.any { it.deviceId == candidate.deviceId }) {
            return failure(SyncFailureReason.INVITE_INVALID)
        }
        val newGeneration = current.registryGeneration + 1
        val epoch = current.epoch
        val newPeer =
            SyncPeerDeviceEntity(
                deviceId = candidate.deviceId,
                name = candidate.deviceName,
                signingPublicKey = candidate.signingPublicKey,
                hpkePublicKey = candidate.hpkePublicKey,
                addedAtGeneration = newGeneration,
                revokedAtGeneration = null,
                lastSyncAt = null,
                lastObjectId = null,
                lastEncryptionCounter = 0,
                isSelf = false,
            )
        val registry =
            buildRegistry(current, peers + newPeer, registryGeneration = newGeneration, epoch = epoch)
        val signedRegistry =
            SyncTrustCodec.encodeSignedRegistry(registry, signingKeyId(), keyManager::sign)
        val snapshotObjectId = uploadSnapshot(backend, requireIdentity())
        val afterSnapshot = requireIdentity()
        val newHead =
            verifiedHead.head.copy(
                generation = (verifiedHead.generation + 1).toString(),
                registryGeneration = newGeneration,
                registryHash = Base64Url.encode(signedRegistry.hash),
                snapshotObjectId = snapshotObjectId ?: verifiedHead.head.snapshotObjectId,
                deviceLogHeads =
                    afterSnapshot.lastUploadedObjectId?.let {
                        verifiedHead.head.deviceLogHeads + (identity.deviceId to it)
                    } ?: verifiedHead.head.deviceLogHeads,
            )
        val signedHead = SyncTrustCodec.encodeSignedHead(newHead, signingKeyId(), keyManager::sign)
        val libraryIdKey =
            SyncTrustCodec.libraryIdKey(
                Base64Url.decodeOrThrow(invite.secret),
                Base64Url.decodeOrThrow(invite.nonce),
            )
        val (libraryIdNonce, libraryIdCiphertext) =
            SyncTrustCodec.encryptLibraryId(
                libraryIdKey,
                candidate.deviceId,
                libraryIdBytes(identity),
            )
        val authorization =
            WireKeyAuthorization(
                protocolVersion = SYNC_PROTOCOL_VERSION,
                suite = SYNC_SUITE_ID,
                libraryOpaqueId = identity.libraryOpaqueId,
                epoch = epoch,
                registryGeneration = newGeneration,
                registryHash = Base64Url.encode(signedRegistry.hash),
                trustedHeadHash = Base64Url.encode(signedHead.hash),
                senderSigningKeyId = Base64Url.encode(signingKeyId()),
                recipientDeviceId = candidate.deviceId,
                recipientHpkePublicKey = candidate.hpkePublicKey,
                expiresAt = SyncWireTime.encode(nowMillis() + SyncInvite.VALIDITY_MILLIS),
                inviteNonce = candidate.inviteNonce,
                bootstrapSnapshotObjectId = snapshotObjectId ?: verifiedHead.head.snapshotObjectId,
                libraryIdNonce = libraryIdNonce,
                libraryIdCiphertext = libraryIdCiphertext,
            )
        val epochKey = unwrapKey(SyncKeyManager.KEY_TYPE_EPOCH, epoch, keyWrapScope(identity))
        val envelopeBytes =
            SyncTrustCodec.sealEpochKeyEnvelope(
                authorization = authorization,
                recipientHpkePublicKey = Base64Url.decodeOrThrow(candidate.hpkePublicKey),
                epochKey = epochKey,
                sign = keyManager::sign,
                inviteSecret = Base64Url.decodeOrThrow(invite.secret),
            )
        epochKey.fill(0)
        val envelopeId = envelopeIdOf(envelopeBytes)
        // 招待をatomicに消費してから配布する（nonce一回性）。
        if (database.withTransaction { keyDao.consumeInvite(invite.nonce, nowMillis()) } != 1) {
            return failure(SyncFailureReason.INVITE_INVALID)
        }
        backend.putRegistryIfAbsent(newGeneration, signedRegistry.bytes)
        backend.putDeviceEnvelopeIfAuthorized(envelopeId, envelopeBytes)
        when (backend.compareAndSetHead(verifiedHead.etag, signedHead.bytes)) {
            is SyncCasResult.Committed -> {
                Unit
            }

            is SyncCasResult.Conflict -> {
                return failure(SyncFailureReason.BACKEND, SyncBackendErrorKind.CAS_CONFLICT)
            }
        }
        runCatching { backend.deleteJoinRequest(candidate.deviceId) }
        database.withTransaction {
            keyDao.upsertPeerDevices(listOf(newPeer))
            keyDao.upsertIdentity(
                requireIdentity().copy(
                    registryGeneration = newGeneration,
                    registryHash = Base64Url.encode(signedRegistry.hash),
                    headGeneration = verifiedHead.generation + 1,
                    trustedHeadHash = Base64Url.encode(signedHead.hash),
                ),
            )
        }
        return SyncActionResult.Success()
    }

    // ---- Revocation ----

    /** 端末失効: registry世代進行と新epochへのrotationを行い、失効端末へwrapしない。 */
    suspend fun revokeDevice(deviceId: String): SyncActionResult {
        consentFailure()?.let { return it }
        val identity = keyDao.getIdentity() ?: return failure(SyncFailureReason.NOT_ENABLED)
        if (!identity.activated) return failure(SyncFailureReason.JOIN_NOT_READY)
        identity.securityLockout?.let { return failure(SyncFailureReason.SECURITY_LOCKOUT) }
        if (deviceId == identity.deviceId) return failure(SyncFailureReason.INTERNAL)
        return try {
            val backend =
                backendFactory.create(identity.backendType, identity.backendConfig, identity.libraryOpaqueId)
            revokeWithBackend(backend, identity, deviceId)
        } catch (error: SyncBackendException) {
            failure(SyncFailureReason.BACKEND, error.kind)
        } catch (_: SyncKeyUnavailableException) {
            failure(SyncFailureReason.KEY_UNAVAILABLE)
        } catch (_: Exception) {
            failure(SyncFailureReason.INTERNAL)
        }
    }

    private suspend fun revokeWithBackend(
        backend: SyncBackend,
        identity: SyncIdentityEntity,
        deviceId: String,
    ): SyncActionResult {
        val target =
            keyDao.findPeerDevice(deviceId)?.takeIf { it.revokedAtGeneration == null }
                ?: return failure(SyncFailureReason.INTERNAL)
        val verifiedHead = fetchAndVerifyHead(backend, identity) ?: return headFailure()
        val current = requireIdentity()
        val newGeneration = current.registryGeneration + 1
        val newEpoch = current.epoch + 1
        val newEpochKey = SyncCrypto.randomBytes(SyncCrypto.EPOCH_KEY_BYTES)
        val peers = keyDao.getPeerDevices()
        val updatedPeers =
            peers.map { peer ->
                if (peer.deviceId == deviceId) peer.copy(revokedAtGeneration = newGeneration) else peer
            }
        val registry =
            buildRegistry(
                current,
                updatedPeers,
                registryGeneration = newGeneration,
                epoch = newEpoch,
                epochKeyOverride = newEpochKey,
            )
        val signedRegistry =
            SyncTrustCodec.encodeSignedRegistry(registry, signingKeyId(), keyManager::sign)
        val newHead =
            verifiedHead.head.copy(
                generation = (verifiedHead.generation + 1).toString(),
                epoch = newEpoch,
                registryGeneration = newGeneration,
                registryHash = Base64Url.encode(signedRegistry.hash),
            )
        val signedHead = SyncTrustCodec.encodeSignedHead(newHead, signingKeyId(), keyManager::sign)
        val recipients =
            updatedPeers.filter { !it.isSelf && it.revokedAtGeneration == null }
        val envelopes =
            recipients.map { peer ->
                val authorization =
                    WireKeyAuthorization(
                        protocolVersion = SYNC_PROTOCOL_VERSION,
                        suite = SYNC_SUITE_ID,
                        libraryOpaqueId = identity.libraryOpaqueId,
                        epoch = newEpoch,
                        registryGeneration = newGeneration,
                        registryHash = Base64Url.encode(signedRegistry.hash),
                        trustedHeadHash = Base64Url.encode(signedHead.hash),
                        senderSigningKeyId = Base64Url.encode(signingKeyId()),
                        recipientDeviceId = peer.deviceId,
                        recipientHpkePublicKey = peer.hpkePublicKey,
                        expiresAt = SyncWireTime.encode(nowMillis() + ROTATION_ENVELOPE_VALIDITY_MILLIS),
                        inviteNonce = Base64Url.encode(SyncCrypto.randomBytes(16)),
                    )
                SyncTrustCodec.sealEpochKeyEnvelope(
                    authorization = authorization,
                    recipientHpkePublicKey = Base64Url.decodeOrThrow(peer.hpkePublicKey),
                    epochKey = newEpochKey,
                    sign = keyManager::sign,
                )
            }
        backend.putRegistryIfAbsent(newGeneration, signedRegistry.bytes)
        envelopes.forEach { bytes ->
            backend.putDeviceEnvelopeIfAuthorized(envelopeIdOf(bytes), bytes)
        }
        when (backend.compareAndSetHead(verifiedHead.etag, signedHead.bytes)) {
            is SyncCasResult.Committed -> {
                Unit
            }

            is SyncCasResult.Conflict -> {
                newEpochKey.fill(0)
                return failure(SyncFailureReason.BACKEND, SyncBackendErrorKind.CAS_CONFLICT)
            }
        }
        database.withTransaction {
            storeWrappedKey(
                SyncKeyManager.KEY_TYPE_EPOCH,
                newEpoch,
                newEpochKey,
                keyWrapScope(identity),
            )
            keyDao.upsertPeerDevices(updatedPeers)
            keyDao.upsertIdentity(
                requireIdentity().copy(
                    epoch = newEpoch,
                    registryGeneration = newGeneration,
                    registryHash = Base64Url.encode(signedRegistry.hash),
                    headGeneration = verifiedHead.generation + 1,
                    trustedHeadHash = Base64Url.encode(signedHead.hash),
                ),
            )
        }
        newEpochKey.fill(0)
        return SyncActionResult.Success()
    }

    // ---- Sync cycle ----

    suspend fun syncNow(): SyncActionResult {
        consentFailure()?.let { return it }
        val identity = keyDao.getIdentity() ?: return failure(SyncFailureReason.NOT_ENABLED)
        if (!identity.activated) return failure(SyncFailureReason.JOIN_NOT_READY)
        identity.securityLockout?.let { return failure(SyncFailureReason.SECURITY_LOCKOUT) }
        var lastFailure: SyncActionResult.Failure? = null
        repeat(MAX_SYNC_ATTEMPTS) { attempt ->
            val result =
                try {
                    val backend =
                        backendFactory.create(
                            identity.backendType,
                            identity.backendConfig,
                            identity.libraryOpaqueId,
                        )
                    runSyncCycle(backend)
                } catch (error: SyncBackendException) {
                    failure(SyncFailureReason.BACKEND, error.kind)
                } catch (_: SyncKeyUnavailableException) {
                    failure(SyncFailureReason.KEY_UNAVAILABLE)
                } catch (_: Exception) {
                    failure(SyncFailureReason.INTERNAL)
                }
            when (result) {
                is SyncActionResult.Failure -> {
                    if (result.failure.retryable && attempt < MAX_SYNC_ATTEMPTS - 1) {
                        lastFailure = result
                        retryDelayMillis(attempt)
                    } else {
                        return result
                    }
                }

                else -> {
                    return result
                }
            }
        }
        return lastFailure ?: failure(SyncFailureReason.INTERNAL)
    }

    private suspend fun runSyncCycle(backend: SyncBackend): SyncActionResult {
        checkCapabilities(backend)
        val identity = requireIdentity()
        val verifiedHead = fetchAndVerifyHead(backend, identity) ?: return headFailure()
        if (requireIdentity().securityLockout != null) {
            return failure(SyncFailureReason.SECURITY_LOCKOUT)
        }
        val selfPeer = keyDao.findPeerDevice(identity.deviceId)
        if (selfPeer?.revokedAtGeneration != null) {
            engine.requireReregistration()
            scheduler.reconcile(false)
            return failure(SyncFailureReason.DEVICE_REVOKED)
        }
        val registry = cachedRegistry() ?: return failure(SyncFailureReason.INTERNAL)
        if (epochKeyOrNull(registry.epoch, requireIdentity()) == null) {
            // rotation envelope未着。次回の同期で再試行する。
            return failure(SyncFailureReason.KEY_UNAVAILABLE)
        }
        val applied = downloadDeviceLogs(backend, verifiedHead)
        if (applied < 0) return failure(SyncFailureReason.SECURITY_LOCKOUT)
        val uploaded = uploadPendingOperations(backend)
        val publishedAck =
            if (uploaded == 0 && applied > 0) {
                publishAcknowledgement(backend)
            } else {
                false
            }
        if (uploaded > 0 || publishedAck) {
            val headResult = updateHead(backend, verifiedHead)
            if (headResult != null) return headResult
        }
        engine.markSyncSucceeded()
        database.withTransaction {
            keyDao.findPeerDevice(requireIdentity().deviceId)?.let { self ->
                keyDao.upsertPeerDevices(listOf(self.copy(lastSyncAt = nowMillis())))
            }
        }
        return SyncActionResult.Success(applied)
    }

    /** 検証済みheadと、CAS用etag・generationを返す。失敗時はnull。 */
    private class VerifiedHeadState(
        val head: WireHead,
        val etag: String,
        val generation: Long,
        val hash: ByteArray,
    )

    private var lastHeadFailure: SyncFailure = SyncFailure(SyncFailureReason.INTERNAL)

    private fun headFailure(): SyncActionResult = SyncActionResult.Failure(lastHeadFailure)

    private suspend fun fetchAndVerifyHead(
        backend: SyncBackend,
        identity: SyncIdentityEntity,
    ): VerifiedHeadState? {
        val record = backend.getHead()
        if (record == null) {
            lastHeadFailure = SyncFailure(SyncFailureReason.LIBRARY_NOT_FOUND)
            return null
        }
        val rawText = record.bytes.toString(Charsets.UTF_8)
        val parsed =
            runCatching {
                SyncWireJson.lenient.decodeFromString(WireSignedHead.serializer(), rawText)
            }.getOrNull()
        if (parsed == null) {
            quarantineAndLock("head", record.bytes, "head parse failure")
            lastHeadFailure = SyncFailure(SyncFailureReason.SECURITY_LOCKOUT)
            return null
        }
        // headが参照するregistry世代まで連鎖検証してから、head署名を検証する。
        if (!refreshRegistryChain(backend, identity, parsed.head.registryGeneration)) {
            lastHeadFailure = SyncFailure(SyncFailureReason.SECURITY_LOCKOUT)
            return null
        }
        val peers = keyDao.getPeerDevices()
        val verified =
            SyncTrustCodec.verifySignedHead(
                bytes = record.bytes,
                expectedLibraryOpaqueId = identity.libraryOpaqueId,
                signerKeyFor = { keyId ->
                    peers
                        .filter { it.revokedAtGeneration == null }
                        .firstNotNullOfOrNull { peer ->
                            Base64Url.decode(peer.signingPublicKey)?.takeIf { der ->
                                Base64Url.encode(SyncCrypto.signingKeyId(der)) == keyId
                            }
                        }
                },
            )
        if (verified == null) {
            quarantineAndLock("head", record.bytes, "head signature failure")
            lastHeadFailure = SyncFailure(SyncFailureReason.SECURITY_LOCKOUT)
            return null
        }
        val generation = requireNotNull(verified.head.generation.toLongOrNull())
        val current = requireIdentity()
        if (generation < current.headGeneration) {
            quarantineAndLock("head", record.bytes, "head rollback detected")
            lastHeadFailure = SyncFailure(SyncFailureReason.SECURITY_LOCKOUT)
            return null
        }
        if (generation == current.headGeneration &&
            current.trustedHeadHash != null &&
            Base64Url.encode(verified.hash) != current.trustedHeadHash
        ) {
            quarantineAndLock("head", record.bytes, "head fork detected")
            lastHeadFailure = SyncFailure(SyncFailureReason.SECURITY_LOCKOUT)
            return null
        }
        val expectedRegistryHash = requireIdentity().registryHash
        if (expectedRegistryHash != null && verified.head.registryHash != expectedRegistryHash) {
            quarantineAndLock("head", record.bytes, "head registry hash mismatch")
            lastHeadFailure = SyncFailure(SyncFailureReason.SECURITY_LOCKOUT)
            return null
        }
        database.withTransaction {
            keyDao.upsertIdentity(
                requireIdentity().copy(
                    headGeneration = generation,
                    trustedHeadHash = Base64Url.encode(verified.hash),
                ),
            )
        }
        return VerifiedHeadState(verified.head, record.etag, generation, verified.hash)
    }

    /** registry chainをlocal cacheのgenerationからtargetまで検証して取り込む。 */
    private suspend fun refreshRegistryChain(
        backend: SyncBackend,
        identity: SyncIdentityEntity,
        targetGeneration: Int,
    ): Boolean {
        var current = requireIdentity()
        if (targetGeneration < current.registryGeneration) return false
        var generation = current.registryGeneration
        while (generation < targetGeneration) {
            val next = generation + 1
            val bytes =
                try {
                    backend.getRegistry(next)
                } catch (error: SyncBackendException) {
                    if (error.kind == SyncBackendErrorKind.NOT_FOUND) return false
                    throw error
                }
            val previousPeers = keyDao.getPeerDevices()
            val verified =
                SyncTrustCodec.verifySignedRegistry(
                    bytes = bytes,
                    expectedGeneration = next,
                    expectedLibraryOpaqueId = identity.libraryOpaqueId,
                    authorizedBy = { keyId ->
                        previousPeers
                            .filter { it.revokedAtGeneration == null }
                            .firstNotNullOfOrNull { peer ->
                                Base64Url.decode(peer.signingPublicKey)?.takeIf { der ->
                                    Base64Url.encode(SyncCrypto.signingKeyId(der)) == keyId
                                }
                            }
                    },
                ) ?: run {
                    quarantineAndLock("registry-$next", bytes, "registry chain verification failure")
                    return false
                }
            if (!validateRegistryContinuity(previousPeers, verified.registry)) {
                quarantineAndLock("registry-$next", bytes, "registry continuity failure")
                return false
            }
            database.withTransaction {
                keyDao.upsertIdentity(
                    requireIdentity().copy(
                        registryGeneration = next,
                        registryHash = Base64Url.encode(verified.hash),
                    ),
                )
            }
            updatePeerCache(verified.registry, isSelfId = identity.deviceId)
            processEnvelopes(backend)
            generation = next
        }
        return true
    }

    private fun validateRegistryContinuity(
        previousPeers: List<SyncPeerDeviceEntity>,
        registry: WireRegistry,
    ): Boolean {
        val byId = registry.devices.associateBy(WireRegistryDevice::deviceId)
        previousPeers.forEach { previous ->
            val next = byId[previous.deviceId] ?: return false
            if (next.signingPublicKey != previous.signingPublicKey) return false
            if (next.hpkePublicKey != previous.hpkePublicKey) return false
            if (previous.revokedAtGeneration != null &&
                next.revokedAtGeneration != previous.revokedAtGeneration
            ) {
                return false
            }
        }
        return registry.devices.size <= MAX_SYNC_DEVICES
    }

    private suspend fun updatePeerCache(
        registry: WireRegistry,
        isSelfId: String,
    ) {
        val identity = requireIdentity()
        val nameKey =
            epochKeyOrNull(registry.epoch, identity)?.let { epochKey ->
                SyncTrustCodec
                    .deviceNameKey(epochKey, libraryIdBytes(identity), registry.epoch)
                    .also { epochKey.fill(0) }
            }
        val existing = keyDao.getPeerDevices().associateBy(SyncPeerDeviceEntity::deviceId)
        val updated =
            registry.devices.map { device ->
                val previous = existing[device.deviceId]
                val name =
                    nameKey?.let {
                        SyncTrustCodec.decryptDeviceName(it, device.deviceId, device.nameNonce, device.nameCiphertext)
                    } ?: previous?.name ?: ""
                SyncPeerDeviceEntity(
                    deviceId = device.deviceId,
                    name = name,
                    signingPublicKey = device.signingPublicKey,
                    hpkePublicKey = device.hpkePublicKey,
                    addedAtGeneration = device.addedAtGeneration,
                    revokedAtGeneration = device.revokedAtGeneration,
                    lastSyncAt = previous?.lastSyncAt,
                    lastObjectId = previous?.lastObjectId,
                    lastEncryptionCounter = previous?.lastEncryptionCounter ?: 0,
                    isSelf = device.deviceId == isSelfId,
                )
            }
        database.withTransaction {
            keyDao.upsertPeerDevices(updated)
            keyDao.deletePeerDevicesNotIn(updated.map(SyncPeerDeviceEntity::deviceId))
        }
    }

    /** 自分宛のrotation envelopeを開き、新epoch keyを取り込む。 */
    private suspend fun processEnvelopes(backend: SyncBackend) {
        val identity = requireIdentity()
        val peers = keyDao.getPeerDevices()
        val hpkePrivateKey =
            try {
                unwrapKey(SyncKeyManager.KEY_TYPE_HPKE_PRIVATE, 1, keyWrapScope(identity))
            } catch (_: SyncKeyUnavailableException) {
                return
            }
        val selfHpkePublicKey = hpkePublicKeyOfSelf(identity)
        try {
            backend.listDeviceEnvelopes().forEach { stored ->
                if (keyDao.findProcessedEnvelope(stored.envelopeId) != null) return@forEach
                val opened =
                    SyncTrustCodec.openEpochKeyEnvelope(
                        bytes = stored.bytes,
                        recipientDeviceId = identity.deviceId,
                        recipientHpkePrivateKey = hpkePrivateKey,
                        recipientHpkePublicKey = selfHpkePublicKey,
                        nowMillis = nowMillis(),
                        senderKeyFor = { keyId ->
                            peers
                                .filter { it.revokedAtGeneration == null }
                                .firstNotNullOfOrNull { peer ->
                                    Base64Url.decode(peer.signingPublicKey)?.takeIf { der ->
                                        Base64Url.encode(SyncCrypto.signingKeyId(der)) == keyId
                                    }
                                }
                        },
                    ) ?: return@forEach
                if (keyDao.countProcessedEnvelopesWithNonce(opened.authorization.inviteNonce) > 0) {
                    return@forEach
                }
                database.withTransaction {
                    storeWrappedKey(
                        SyncKeyManager.KEY_TYPE_EPOCH,
                        opened.authorization.epoch,
                        opened.epochKey,
                        keyWrapScope(identity),
                    )
                    keyDao.insertProcessedEnvelope(
                        SyncProcessedEnvelopeEntity(
                            stored.envelopeId,
                            opened.authorization.inviteNonce,
                            nowMillis(),
                        ),
                    )
                    keyDao.upsertIdentity(
                        requireIdentity().copy(
                            epoch = maxOf(requireIdentity().epoch, opened.authorization.epoch),
                        ),
                    )
                }
                opened.epochKey.fill(0)
            }
        } finally {
            hpkePrivateKey.fill(0)
        }
    }

    /** device logを逆walkして検証・復号し、engineへ適用する。負値はsecurity停止。 */
    private suspend fun downloadDeviceLogs(
        backend: SyncBackend,
        verifiedHead: VerifiedHeadState,
    ): Int {
        val identity = requireIdentity()
        val registry = cachedRegistry() ?: return -1
        var applied = 0
        val epochKeys = loadEpochKeys(identity)
        try {
            return downloadDeviceLogsWithKeys(backend, verifiedHead, identity, registry, epochKeys)
        } finally {
            epochKeys.values.forEach { it.fill(0) }
        }
    }

    private suspend fun downloadDeviceLogsWithKeys(
        backend: SyncBackend,
        verifiedHead: VerifiedHeadState,
        identity: SyncIdentityEntity,
        registry: SyncDeviceRegistry,
        epochKeys: Map<Int, ByteArray>,
    ): Int {
        var applied = 0
        for ((deviceId, headObjectId) in verifiedHead.head.deviceLogHeads) {
            if (deviceId == identity.deviceId) continue
            val peer = keyDao.findPeerDevice(deviceId) ?: continue
            if (headObjectId == peer.lastObjectId) continue
            val processedVector = engine.currentProcessedVector()
            val chain = mutableListOf<Triple<String, ByteArray, SyncObjectCodec.OpenResult.Valid>>()
            var cursor: String? = headObjectId
            var depth = 0
            var securityStop = false
            while (cursor != null && cursor != peer.lastObjectId && depth < MAX_CHAIN_DEPTH) {
                val bytes = backend.getObject(cursor)
                when (
                    val opened =
                        SyncObjectCodec.open(
                            bytes = bytes,
                            expectedObjectId = cursor,
                            libraryOpaqueId = identity.libraryOpaqueId,
                            libraryId = libraryIdBytes(identity),
                            registry = registry,
                            maxObjectSizeBytes = maxObjectBytes,
                            epochKeyProvider = { epoch -> epochKeys[epoch] },
                        )
                ) {
                    is SyncObjectCodec.OpenResult.Invalid -> {
                        quarantineAndLock(cursor, bytes, opened.reason)
                        securityStop = true
                        cursor = null
                    }

                    is SyncObjectCodec.OpenResult.Valid -> {
                        val operations = opened.payload as? WirePayload.Operations
                        if (operations == null || opened.sender.deviceId != deviceId) {
                            quarantineAndLock(cursor, bytes, "unexpected payload in device log")
                            securityStop = true
                            cursor = null
                        } else {
                            chain += Triple(cursor, bytes, opened)
                            val lastCounter =
                                operations.payload.counterRange
                                    ?.last
                                    ?.toLongOrNull()
                            cursor =
                                if (lastCounter != null && lastCounter <= processedVector[deviceId]) {
                                    null
                                } else {
                                    operations.payload.previousObjectHash
                                }
                        }
                    }
                }
                depth += 1
            }
            if (securityStop) return -1
            var lastEncryptionCounter = if (peer.lastObjectId == null) 0L else peer.lastEncryptionCounter
            for ((objectId, bytes, valid) in chain.asReversed()) {
                val payload = (valid.payload as WirePayload.Operations).payload
                val nonceCounter = encryptionCounterOf(bytes)
                if (nonceCounter == null || (lastEncryptionCounter != 0L && nonceCounter <= lastEncryptionCounter)) {
                    quarantineAndLock(objectId, bytes, "encryption counter reuse or rollback")
                    return -1
                }
                lastEncryptionCounter = nonceCounter
                val operations = SyncWireConverters.fromWireOperationsPayload(payload)
                if (operations == null) {
                    quarantineAndLock(objectId, bytes, "operation schema failure")
                    return -1
                }
                val vector = SyncWireConverters.fromWireVector(payload.versionVector)
                if (vector == null) {
                    quarantineAndLock(objectId, bytes, "version vector schema failure")
                    return -1
                }
                applied +=
                    try {
                        ingestOperations(operations)
                    } catch (_: Exception) {
                        quarantineAndLock(objectId, bytes, "operation validation failure")
                        return -1
                    }
                engine.recordAcknowledgement(deviceId, vector)
                database.withTransaction {
                    keyDao.findPeerDevice(deviceId)?.let { latest ->
                        keyDao.upsertPeerDevices(
                            listOf(
                                latest.copy(
                                    lastObjectId = objectId,
                                    lastEncryptionCounter = lastEncryptionCounter,
                                    lastSyncAt =
                                        maxOf(
                                            latest.lastSyncAt ?: 0,
                                            SyncWireTime.decode(payload.createdAt) ?: 0,
                                        ).takeIf { it > 0 },
                                ),
                            ),
                        )
                    }
                }
            }
            if (chain.isEmpty() && peer.lastObjectId != headObjectId) {
                // counterが既に処理済みでchainを辿らなかった場合もheadを記録する。
                database.withTransaction {
                    keyDao.findPeerDevice(deviceId)?.let { latest ->
                        keyDao.upsertPeerDevices(listOf(latest.copy(lastObjectId = headObjectId)))
                    }
                }
            }
        }
        return applied
    }

    private suspend fun ingestOperations(operations: List<SyncOperation>): Int {
        if (operations.isEmpty()) return 0
        var applied = 0
        operations.chunked(1_000).forEach { chunk ->
            applied += engine.ingest(chunk)
        }
        return applied
    }

    /**
     * LOCAL_PENDING operationを暗号化objectとしてuploadし、object保存成功ごとに
     * ACKNOWLEDGEDへ進める。objectはimmutableなためhead更新失敗後も次回の
     * head CASで回収できる。件数を返す。
     */
    private suspend fun uploadPendingOperations(backend: SyncBackend): Int {
        var uploadedCount = 0
        while (true) {
            var pending = engine.pendingOperations(UPLOAD_CHUNK_OPERATIONS)
            if (pending.isEmpty()) break
            var chunk = wholeTransactionPrefix(pending)
            if (chunk.isEmpty()) {
                // 単一transactionがchunkを超える場合は上限1,000で取り直す。
                pending = engine.pendingOperations(MAX_SYNC_BATCH_OPERATIONS)
                chunk = wholeTransactionPrefix(pending).ifEmpty { pending }
            }
            val current = requireIdentity()
            val payload =
                SyncWireConverters.toWireOperationsPayload(
                    deviceId = current.deviceId,
                    previousObjectHash = current.lastUploadedObjectId,
                    operations = chunk,
                    versionVector = engine.currentProcessedVector(),
                    nowMillis = nowMillis(),
                )
            val objectId = sealAndPut(backend, payloadBytes(payload))
            uploadedCount += chunk.size
            database.withTransaction {
                keyDao.upsertIdentity(requireIdentity().copy(lastUploadedObjectId = objectId))
            }
            engine.markUploaded(chunk.map(SyncOperation::operationId))
        }
        return uploadedCount
    }

    /** transaction境界を跨がない先頭部分列を切り出す。 */
    private fun wholeTransactionPrefix(operations: List<SyncOperation>): List<SyncOperation> {
        if (operations.isEmpty()) return operations
        val lastTransactionId = operations.last().transactionId
        val lastComplete =
            operations.count { it.transactionId == lastTransactionId } ==
                operations.last().transactionSize
        return if (lastComplete) operations else operations.dropLastWhile { it.transactionId == lastTransactionId }
    }

    /** 変更なしでも処理済みvectorを共有するacknowledgement object。 */
    private suspend fun publishAcknowledgement(backend: SyncBackend): Boolean {
        val current = requireIdentity()
        val payload =
            SyncWireConverters.toWireOperationsPayload(
                deviceId = current.deviceId,
                previousObjectHash = current.lastUploadedObjectId,
                operations = emptyList(),
                versionVector = engine.currentProcessedVector(),
                nowMillis = nowMillis(),
            )
        val objectId = sealAndPut(backend, payloadBytes(payload))
        database.withTransaction {
            keyDao.upsertIdentity(requireIdentity().copy(lastUploadedObjectId = objectId))
        }
        return true
    }

    private fun payloadBytes(payload: dev.ndcshelf.app.data.sync.protocol.WireOperationsPayload): ByteArray =
        SyncWireJson.canonicalEncode(payload)

    private suspend fun sealAndPut(
        backend: SyncBackend,
        canonicalPayload: ByteArray,
    ): String {
        val identity = requireIdentity()
        val epochKey = unwrapKey(SyncKeyManager.KEY_TYPE_EPOCH, identity.epoch, keyWrapScope(identity))
        val subkey =
            SyncCrypto.deriveContentSubkey(
                epochKey,
                libraryIdBytes(identity),
                identity.epoch,
                Base64Url.decodeOrThrow(identity.deviceId),
            )
        epochKey.fill(0)
        val counter = nextEncryptionCounter()
        val sealed =
            SyncObjectCodec.seal(
                payloadCanonical = canonicalPayload,
                libraryOpaqueId = identity.libraryOpaqueId,
                epoch = identity.epoch,
                registryGeneration = identity.registryGeneration,
                contentSubkey = subkey,
                encryptionCounter = counter,
                signingKeyId = signingKeyId(),
                sign = keyManager::sign,
            )
        subkey.fill(0)
        backend.putObjectIfAbsent(sealed.objectId, sealed.bytes)
        return sealed.objectId
    }

    /** snapshot objectのchainをuploadし、chain headのobjectIdを返す。 */
    private suspend fun uploadSnapshot(
        backend: SyncBackend,
        identity: SyncIdentityEntity,
    ): String? {
        val snapshot = engine.exportSnapshot()
        if (snapshot.fieldStates.isEmpty() && snapshot.tombstones.isEmpty()) return null
        var previous: String? = null
        val chunks = snapshot.fieldStates.chunked(SNAPSHOT_CHUNK_FIELDS).ifEmpty { listOf(emptyList()) }
        chunks.forEachIndexed { index, chunk ->
            val payload =
                SyncWireConverters.toWireSnapshotPayload(
                    deviceId = identity.deviceId,
                    previousObjectHash = previous,
                    snapshot =
                        SyncSnapshotData(
                            fieldStates = chunk,
                            tombstones = if (index == chunks.lastIndex) snapshot.tombstones else emptyList(),
                            versionVector = snapshot.versionVector,
                        ),
                    nowMillis = nowMillis(),
                )
            previous = sealAndPut(backend, SyncWireJson.canonicalEncode(payload))
        }
        return previous
    }

    private suspend fun updateHead(
        backend: SyncBackend,
        verifiedHead: VerifiedHeadState,
    ): SyncActionResult? {
        var expected = verifiedHead
        repeat(MAX_CAS_ATTEMPTS) {
            val identity = requireIdentity()
            val newHead =
                expected.head.copy(
                    generation = (expected.generation + 1).toString(),
                    deviceLogHeads =
                        identity.lastUploadedObjectId?.let {
                            expected.head.deviceLogHeads + (identity.deviceId to it)
                        } ?: expected.head.deviceLogHeads,
                )
            val signedHead = SyncTrustCodec.encodeSignedHead(newHead, signingKeyId(), keyManager::sign)
            when (backend.compareAndSetHead(expected.etag, signedHead.bytes)) {
                is SyncCasResult.Committed -> {
                    database.withTransaction {
                        keyDao.upsertIdentity(
                            requireIdentity().copy(
                                headGeneration = expected.generation + 1,
                                trustedHeadHash = Base64Url.encode(signedHead.hash),
                            ),
                        )
                    }
                    return null
                }

                is SyncCasResult.Conflict -> {
                    val refreshed = fetchAndVerifyHead(backend, requireIdentity()) ?: return headFailure()
                    val applied = downloadDeviceLogs(backend, refreshed)
                    if (applied < 0) return failure(SyncFailureReason.SECURITY_LOCKOUT)
                    expected = refreshed
                }
            }
        }
        return failure(SyncFailureReason.BACKEND, SyncBackendErrorKind.CAS_CONFLICT)
    }

    // ---- Sign-out and remote deletion ----

    /** 同期停止（sign-out）。local domain dataは変更しない。 */
    suspend fun stopSync() {
        scheduler.reconcile(false)
        database.withTransaction {
            resetSyncStateAfterDomainRestore(
                database.syncDao(),
                keyDao,
                requiresReregistration = false,
            )
        }
        runCatching(keyManager::destroyDeviceKeys)
        lastCreatedInvite = null
    }

    /**
     * remote全削除（8.4節）。全object・head・wrapped key・registryを削除し、
     * 完了確認receiptを返す。local domain dataは変更しない。
     */
    suspend fun purgeRemote(): Result<SyncDeletionReceipt> {
        val identity =
            keyDao.getIdentity()
                ?: return Result.failure(IllegalStateException("Sync is not configured."))
        return try {
            val backend =
                backendFactory.create(identity.backendType, identity.backendConfig, identity.libraryOpaqueId)
            val receipt = backend.requestRemoteDeletion()
            if (receipt.remainingObjectCount == 0) stopSync()
            Result.success(receipt)
        } catch (error: SyncBackendException) {
            Result.failure(error)
        }
    }

    // ---- Shared helpers ----

    private suspend fun consentFailure(): SyncActionResult.Failure? =
        if (consentRepository.isGranted(ConsentPurpose.LIBRARY_SYNC)) {
            null
        } else {
            SyncActionResult.Failure(SyncFailure(SyncFailureReason.CONSENT_REQUIRED))
        }

    private fun failure(
        reason: SyncFailureReason,
        kind: SyncBackendErrorKind? = null,
    ): SyncActionResult.Failure = SyncActionResult.Failure(SyncFailure(reason, kind))

    private suspend fun requireIdentity(): SyncIdentityEntity = requireNotNull(keyDao.getIdentity()) { "Sync identity is missing." }

    private suspend fun checkCapabilities(backend: SyncBackend) {
        val capabilities = backend.getCapabilities()
        if (capabilities.protocolMajor != SYNC_PROTOCOL_MAJOR ||
            SYNC_SUITE_ID !in capabilities.suites ||
            !capabilities.supportsCompareAndSet
        ) {
            throw SyncBackendException(
                SyncBackendErrorKind.INCOMPATIBLE_CAPABILITY,
                "The backend does not support the required capabilities.",
            )
        }
    }

    private fun signingKeyId(): ByteArray = SyncCrypto.signingKeyId(keyManager.signingPublicKeyDer())

    private suspend fun storeWrappedKey(
        keyType: String,
        keyVersion: Int,
        plaintext: ByteArray,
        scope: String,
    ) {
        val blob = keyManager.wrap(keyType, scope, keyVersion, plaintext)
        keyDao.upsertWrappedKey(
            SyncWrappedKeyEntity(
                keyType = keyType,
                keyVersion = keyVersion,
                nonce = Base64Url.encode(blob.nonce),
                ciphertext = Base64Url.encode(blob.ciphertext),
                keyAliasVersion = blob.keyAliasVersion,
                createdAt = nowMillis(),
            ),
        )
    }

    private suspend fun unwrapKey(
        keyType: String,
        keyVersion: Int,
        scope: String,
    ): ByteArray {
        val row =
            keyDao.findWrappedKey(keyType, keyVersion)
                ?: throw SyncKeyUnavailableException("A wrapped sync key is missing.")
        return keyManager.unwrap(
            keyType,
            scope,
            keyVersion,
            WrappedKeyBlob(
                nonce = Base64Url.decodeOrThrow(row.nonce),
                ciphertext = Base64Url.decodeOrThrow(row.ciphertext),
                keyAliasVersion = row.keyAliasVersion,
            ),
        )
    }

    private suspend fun epochKeyOrNull(
        epoch: Int,
        identity: SyncIdentityEntity,
    ): ByteArray? =
        try {
            unwrapKey(SyncKeyManager.KEY_TYPE_EPOCH, epoch, keyWrapScope(identity))
        } catch (_: SyncKeyUnavailableException) {
            null
        }

    /** wrap AADのlibrary識別子。creatorはlibraryId、joinerはopaqueIdで束縛する。 */
    private fun keyWrapScope(identity: SyncIdentityEntity): String = identity.libraryOpaqueId

    private fun libraryIdBytes(identity: SyncIdentityEntity): ByteArray {
        val decoded = Base64Url.decodeOrThrow(identity.libraryId)
        check(decoded.size == SyncCrypto.LIBRARY_ID_BYTES) { "The sync library ID is not available yet." }
        return decoded
    }

    private suspend fun hpkePublicKeyOfSelf(identity: SyncIdentityEntity): ByteArray {
        val self =
            keyDao.findPeerDevice(identity.deviceId)
                ?: throw SyncKeyUnavailableException("The own device entry is missing.")
        return Base64Url.decodeOrThrow(self.hpkePublicKey)
    }

    private suspend fun cachedRegistry(): SyncDeviceRegistry? {
        val identity = keyDao.getIdentity() ?: return null
        val peers = keyDao.getPeerDevices()
        if (peers.isEmpty()) return null
        return SyncDeviceRegistry(
            libraryOpaqueId = identity.libraryOpaqueId,
            registryGeneration = identity.registryGeneration,
            epoch = maxOf(identity.epoch, 1),
            devices =
                peers.map { peer ->
                    dev.ndcshelf.app.domain.sync.SyncRegistryDevice(
                        deviceId = peer.deviceId,
                        name = peer.name,
                        signingPublicKey = peer.signingPublicKey,
                        hpkePublicKey = peer.hpkePublicKey,
                        addedAtGeneration = peer.addedAtGeneration,
                        revokedAtGeneration = peer.revokedAtGeneration,
                    )
                },
        )
    }

    private suspend fun buildRegistry(
        identity: SyncIdentityEntity,
        peers: List<SyncPeerDeviceEntity>,
        registryGeneration: Int,
        epoch: Int,
        epochKeyOverride: ByteArray? = null,
    ): WireRegistry {
        val epochKey = epochKeyOverride ?: epochKeyOrNull(epoch, identity)
        val nameKey = epochKey?.let { SyncTrustCodec.deviceNameKey(it, libraryIdBytes(identity), epoch) }
        if (epochKeyOverride == null) epochKey?.fill(0)
        return WireRegistry(
            protocolVersion = SYNC_PROTOCOL_VERSION,
            suite = SYNC_SUITE_ID,
            libraryOpaqueId = identity.libraryOpaqueId,
            registryGeneration = registryGeneration,
            epoch = epoch,
            devices =
                peers.map { peer ->
                    val (nameNonce, nameCiphertext) =
                        nameKey?.let { SyncTrustCodec.encryptDeviceName(it, peer.deviceId, peer.name) }
                            ?: ("" to "")
                    WireRegistryDevice(
                        deviceId = peer.deviceId,
                        signingPublicKey = peer.signingPublicKey,
                        hpkePublicKey = peer.hpkePublicKey,
                        addedAtGeneration = peer.addedAtGeneration,
                        revokedAtGeneration = peer.revokedAtGeneration,
                        nameNonce = nameNonce,
                        nameCiphertext = nameCiphertext,
                    )
                },
        )
    }

    /** 保持している全epoch keyを一時的にmemoryへ展開する。使用後にzeroizeする。 */
    private suspend fun loadEpochKeys(identity: SyncIdentityEntity): Map<Int, ByteArray> =
        (1..maxOf(identity.epoch, 1))
            .mapNotNull { epoch -> epochKeyOrNull(epoch, identity)?.let { epoch to it } }
            .toMap()

    private suspend fun nextEncryptionCounter(): Long =
        database.withTransaction {
            check(keyDao.incrementEncryptionCounter() == 1) {
                "The encryption counter is unavailable or exhausted."
            }
            requireNotNull(keyDao.getEncryptionCounter())
        }

    private fun encryptionCounterOf(envelopeBytes: ByteArray): Long? {
        val envelope =
            runCatching {
                SyncWireJson.lenient.decodeFromString(
                    dev.ndcshelf.app.data.sync.protocol.WireSyncEnvelope
                        .serializer(),
                    envelopeBytes.toString(Charsets.UTF_8),
                )
            }.getOrNull() ?: return null
        val nonce = Base64Url.decode(envelope.nonce) ?: return null
        if (nonce.size != SyncCrypto.NONCE_BYTES) return null
        var value = 0L
        for (index in 4 until 12) {
            value = (value shl 8) or (nonce[index].toLong() and 0xFF)
        }
        return value.takeIf { it >= 1 }
    }

    private fun envelopeIdOf(envelopeBytes: ByteArray): String {
        val envelope =
            SyncWireJson.lenient.decodeFromString(
                dev.ndcshelf.app.data.sync.protocol.WireKeyEnvelope
                    .serializer(),
                envelopeBytes.toString(Charsets.UTF_8),
            )
        return envelope.envelopeId
    }

    private suspend fun quarantineAndLock(
        objectId: String,
        bytes: ByteArray,
        reason: String,
    ) {
        database.withTransaction {
            if (keyDao.countQuarantine() < MAX_QUARANTINE_ENTRIES) {
                val truncated = bytes.size > MAX_QUARANTINE_BYTES
                keyDao.insertQuarantine(
                    SyncQuarantineEntity(
                        objectId = objectId,
                        reason = reason,
                        bytes = if (truncated) bytes.copyOf(MAX_QUARANTINE_BYTES) else bytes,
                        truncated = truncated,
                        receivedAt = nowMillis(),
                    ),
                )
            }
            keyDao.getIdentity()?.let { identity ->
                keyDao.upsertIdentity(identity.copy(securityLockout = reason))
            }
        }
    }

    private suspend fun abandonSyncState(requiresReregistration: Boolean) {
        database.withTransaction {
            resetSyncStateAfterDomainRestore(
                database.syncDao(),
                keyDao,
                requiresReregistration = requiresReregistration,
            )
        }
        runCatching(keyManager::destroyDeviceKeys)
    }

    private val maxObjectBytes: Long
        get() = dev.ndcshelf.app.data.sync.backend.FolderSyncBackend.MAX_OBJECT_BYTES

    private companion object {
        const val MAX_CHAIN_DEPTH = 10_000
        const val MAX_SYNC_ATTEMPTS = 3
        const val MAX_CAS_ATTEMPTS = 3
        const val UPLOAD_CHUNK_OPERATIONS = 500
        const val SNAPSHOT_CHUNK_FIELDS = 2_000
        const val MAX_QUARANTINE_ENTRIES = 50
        const val MAX_QUARANTINE_BYTES = 256 * 1024
        const val ROTATION_ENVELOPE_VALIDITY_MILLIS = 90L * 24 * 60 * 60 * 1_000
    }
}
