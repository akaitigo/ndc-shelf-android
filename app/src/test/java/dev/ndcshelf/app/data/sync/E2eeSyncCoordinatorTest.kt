package dev.ndcshelf.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.SyncIdentityEntity
import dev.ndcshelf.app.data.sync.backend.FileSyncObjectStore
import dev.ndcshelf.app.data.sync.backend.FolderSyncBackend
import dev.ndcshelf.app.data.sync.backend.SyncBackendFactory
import dev.ndcshelf.app.data.sync.backend.SyncObjectStore
import dev.ndcshelf.app.data.sync.crypto.Base64Url
import dev.ndcshelf.app.data.sync.crypto.FakeSyncKeyManager
import dev.ndcshelf.app.data.sync.protocol.SyncWireJson
import dev.ndcshelf.app.data.sync.protocol.WireSyncEnvelope
import dev.ndcshelf.app.domain.consent.ConsentPurpose
import dev.ndcshelf.app.domain.consent.ConsentRecord
import dev.ndcshelf.app.domain.consent.ConsentRepository
import dev.ndcshelf.app.domain.sync.LibrarySyncScheduler
import dev.ndcshelf.app.domain.sync.SyncActionResult
import dev.ndcshelf.app.domain.sync.SyncBackend
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncBackendException
import dev.ndcshelf.app.domain.sync.SyncFailureReason
import dev.ndcshelf.app.domain.sync.SyncMutation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * 2端末（fake keystore×2、実Tink、tempフォルダbackend）での
 * key wrap→unwrap→暗号化op交換→収束と、失効・rotation・fail-closedの検証。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class E2eeSyncCoordinatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val databases = mutableListOf<AppDatabase>()

    @After
    fun tearDown() {
        databases.forEach(AppDatabase::close)
        databases.clear()
    }

    // ---- Device harness ----

    private inner class Device(
        val name: String,
        val root: File,
        val clock: MutableClock = MutableClock(),
    ) {
        val database: AppDatabase =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
                .also(databases::add)
        val engine = RoomSyncEngine(database, RoomSyncDomainStore(database), nowMillis = clock::now)
        val keyManager = FakeSyncKeyManager()
        val consent = FakeConsentRepository()
        val scheduler = RecordingScheduler()
        val store = CountingObjectStore(FileSyncObjectStore(root))
        val factory = TestBackendFactory(store, clock)
        val coordinator =
            E2eeSyncCoordinator(
                database = database,
                engine = engine,
                keyManager = keyManager,
                backendFactory = factory,
                consentRepository = consent,
                scheduler = scheduler,
                nowMillis = clock::now,
                retryDelayMillis = { },
            )

        suspend fun addBook(
            workId: String,
            title: String,
        ) {
            database.libraryDao().upsertWorks(listOf(BookWorkEntity(workId, title, "著者")))
            database.libraryDao().upsertEditions(
                listOf(
                    BookEditionEntity(
                        id = "edition-$workId",
                        workId = workId,
                        isbn13 = null,
                        publisher = null,
                        publishedYear = null,
                        coverUrl = null,
                        ndcCode = null,
                        ndcEdition = null,
                        classificationSource = "MANUAL",
                        bibliographicSource = "MANUAL",
                    ),
                ),
            )
            database.libraryDao().upsertCopies(
                listOf(
                    OwnedCopyEntity(
                        id = "copy-$workId",
                        editionId = "edition-$workId",
                        mediaType = "PHYSICAL",
                        location = "書斎",
                        readingStatus = "UNREAD",
                        addedAt = clock.now(),
                        tierId = null,
                        shelfOrderKey = null,
                        copyLabel = "所蔵本",
                    ),
                ),
            )
            engine.record(
                listOf(
                    database
                        .libraryDao()
                        .getAllWorks()
                        .first { it.id == workId }
                        .toSyncUpsert(),
                    database
                        .libraryDao()
                        .getAllEditions()
                        .first { it.id == "edition-$workId" }
                        .toSyncUpsert(),
                    database
                        .libraryDao()
                        .getAllCopies()
                        .first { it.id == "copy-$workId" }
                        .toSyncUpsert(),
                ),
            )
        }

        suspend fun titles(): List<String> =
            database
                .libraryDao()
                .getAllWorks()
                .map(BookWorkEntity::title)
                .sorted()

        suspend fun identity(): SyncIdentityEntity? = database.syncKeyDao().getIdentity()
    }

    private fun device(name: String) = Device(name, folder.root)

    private suspend fun enableFirstDevice(device: Device): SyncActionResult {
        device.consent.grant(ConsentPurpose.LIBRARY_SYNC)
        return device.coordinator.createLibrary(device.name, BACKEND_TYPE, BACKEND_CONFIG)
    }

    /** 招待→参加→承認→完了までの端末追加手順を実行する。 */
    private suspend fun joinSecondDevice(
        first: Device,
        second: Device,
    ) {
        second.consent.grant(ConsentPurpose.LIBRARY_SYNC)
        assertTrue(first.coordinator.createInvite() is SyncActionResult.Success)
        val invite = requireNotNull(first.coordinator.lastCreatedInvite)

        val joinResult =
            second.coordinator.joinLibrary(second.name, BACKEND_TYPE, BACKEND_CONFIG, invite.encode())
        assertTrue("join should be pending: $joinResult", joinResult is SyncActionResult.JoinPending)
        val joinerCode = (joinResult as SyncActionResult.JoinPending).verificationCode

        val candidates = first.coordinator.pendingJoinRequests()
        assertEquals(1, candidates.size)
        // 双方の端末が同じverification codeを表示する。
        assertEquals(joinerCode, candidates.single().verificationCode)
        assertEquals(second.name, candidates.single().deviceName)

        val approval = first.coordinator.approveJoin(candidates.single())
        assertTrue("approve failed: $approval", approval is SyncActionResult.Success)

        val completion = second.coordinator.completeJoin()
        assertTrue("completeJoin failed: $completion", completion is SyncActionResult.Success)
    }

    // ---- Tests ----

    @Test
    fun twoDevicesExchangeEncryptedOperationsAndConverge() =
        runBlocking {
            val first = device("端末A")
            first.addBook("work-a", "既存の本")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)
            assertTrue(requireNotNull(first.identity()).activated)

            val second = device("端末B")
            joinSecondDevice(first, second)

            // bootstrap snapshotで既存蔵書が引き継がれる。
            assertEquals(listOf("既存の本"), second.titles())

            // 双方向のoperation交換で収束する。
            second.addBook("work-b", "端末Bの本")
            assertTrue(second.coordinator.syncNow() is SyncActionResult.Success)
            assertTrue(first.coordinator.syncNow() is SyncActionResult.Success)
            assertEquals(listOf("既存の本", "端末Bの本"), first.titles())

            first.addBook("work-c", "端末Cの本")
            assertTrue(first.coordinator.syncNow() is SyncActionResult.Success)
            assertTrue(second.coordinator.syncNow() is SyncActionResult.Success)
            assertEquals(listOf("既存の本", "端末Bの本", "端末Cの本"), second.titles())

            // 端末一覧に2台が並び、最終同期時刻を持つ。
            val devices = first.coordinator.observeDevices().first()
            assertEquals(2, devices.size)
            assertEquals(1, devices.count { it.isSelf })
            assertTrue(devices.none { it.revoked })
        }

    @Test
    fun storedObjectsContainNoPlaintextTitles() =
        runBlocking {
            val first = device("端末A")
            first.addBook("work-a", "秘密のタイトル")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)

            val stored = allStoredFiles()
            assertTrue(stored.isNotEmpty())
            stored.forEach { file ->
                val text = file.readText()
                assertFalse("plaintext leaked in ${file.name}", text.contains("秘密のタイトル"))
                assertFalse("plaintext leaked in ${file.name}", text.contains("端末A"))
            }
        }

    @Test
    fun everyUploadedObjectUsesAUniqueNonce() =
        runBlocking {
            val first = device("端末A")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)
            repeat(4) { index ->
                first.addBook("work-$index", "本$index")
                assertTrue(first.coordinator.syncNow() is SyncActionResult.Success)
            }
            val nonces =
                allStoredFiles()
                    .filter { it.parentFile?.name == "objects" }
                    .map { file ->
                        SyncWireJson.lenient
                            .decodeFromString(WireSyncEnvelope.serializer(), file.readText())
                            .nonce
                    }
            assertTrue(nonces.size >= 4)
            assertEquals(nonces.size, nonces.toSet().size)
            // counterは1から単調増加し、再利用しない。
            val counters =
                nonces.map { nonce ->
                    val bytes = Base64Url.decodeOrThrow(nonce)
                    (4 until 12).fold(0L) { acc, index -> (acc shl 8) or (bytes[index].toLong() and 0xFF) }
                }
            assertEquals(counters.size, counters.toSet().size)
            assertTrue(counters.all { it >= 1 })
            assertEquals(counters.max(), requireNotNull(first.identity()).encryptionCounter)
        }

    @Test
    fun revokedDeviceLosesFutureAccessAndItsOperationsAreRejected() =
        runBlocking {
            val first = device("端末A")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)
            val second = device("端末B")
            joinSecondDevice(first, second)
            val secondDeviceId = requireNotNull(second.identity()).deviceId
            val epochBefore = requireNotNull(first.identity()).epoch

            val revoke = first.coordinator.revokeDevice(secondDeviceId)
            assertTrue("revoke failed: $revoke", revoke is SyncActionResult.Success)

            // epoch rotationとregistry世代進行が起きる。
            val afterRevoke = requireNotNull(first.identity())
            assertEquals(epochBefore + 1, afterRevoke.epoch)
            assertTrue(afterRevoke.registryGeneration > 1)
            val revoked =
                first.coordinator
                    .observeDevices()
                    .first()
                    .first { it.deviceId == secondDeviceId }
            assertTrue(revoked.revoked)

            // 失効端末は新epochの鍵を得られず、同期できない。
            second.addBook("work-after", "失効後の本")
            val secondSync = second.coordinator.syncNow()
            assertTrue("revoked device must fail: $secondSync", secondSync is SyncActionResult.Failure)
            assertTrue(
                (secondSync as SyncActionResult.Failure).failure.reason in
                    setOf(SyncFailureReason.DEVICE_REVOKED, SyncFailureReason.KEY_UNAVAILABLE),
            )

            // 失効後も第一端末は自身のデータを同期でき、失効端末の変更は取り込まない。
            first.addBook("work-post", "失効後の第一端末")
            assertTrue(first.coordinator.syncNow() is SyncActionResult.Success)
            assertFalse(first.titles().contains("失効後の本"))
        }

    @Test
    fun consentIsRequiredBeforeAnyBackendKeyOrFileAccess() =
        runBlocking {
            val device = device("端末A")
            // 同意なしでは全操作がCONSENT_REQUIREDで拒否される。
            listOf(
                device.coordinator.createLibrary(device.name, BACKEND_TYPE, BACKEND_CONFIG),
                device.coordinator.joinLibrary(device.name, BACKEND_TYPE, BACKEND_CONFIG, "a.b"),
                device.coordinator.syncNow(),
                device.coordinator.createInvite(),
                device.coordinator.revokeDevice("other"),
                device.coordinator.completeJoin(),
            ).forEach { result ->
                assertTrue(result is SyncActionResult.Failure)
                assertEquals(
                    SyncFailureReason.CONSENT_REQUIRED,
                    (result as SyncActionResult.Failure).failure.reason,
                )
            }
            assertTrue(device.coordinator.pendingJoinRequests().isEmpty())

            // 鍵生成・backend生成・ファイルアクセスが一切起きていない。
            assertEquals(0, device.keyManager.ensureCount)
            assertEquals(0, device.keyManager.signCount)
            assertEquals(0, device.factory.createCount)
            assertEquals(0, device.store.accessCount)
            assertNull(device.identity())
            assertTrue(
                folder.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun consentWithdrawalStopsFurtherSyncAndScheduledWork() =
        runBlocking {
            val device = device("端末A")
            assertTrue(enableFirstDevice(device) is SyncActionResult.Success)
            assertEquals(listOf(true), device.scheduler.calls)

            device.consent.revoke(ConsentPurpose.LIBRARY_SYNC)
            val accessBefore = device.store.accessCount
            val result = device.coordinator.syncNow()
            assertTrue(result is SyncActionResult.Failure)
            assertEquals(
                SyncFailureReason.CONSENT_REQUIRED,
                (result as SyncActionResult.Failure).failure.reason,
            )
            // 撤回後は保存先へ一切アクセスしない。
            assertEquals(accessBefore, device.store.accessCount)
        }

    @Test
    fun stopSyncKeepsLocalDataAndRemoteObjects() =
        runBlocking {
            val device = device("端末A")
            device.addBook("work-a", "端末内の本")
            assertTrue(enableFirstDevice(device) is SyncActionResult.Success)
            val remoteFilesBefore = allStoredFiles().size
            assertTrue(remoteFilesBefore > 0)

            device.coordinator.stopSync()

            // local domain dataは不変、同期stateと鍵は消える。
            assertEquals(listOf("端末内の本"), device.titles())
            assertNull(device.identity())
            assertTrue(device.keyManager.destroyed)
            assertEquals(listOf(true, false), device.scheduler.calls)
            // remote objectは自動削除しない（ADR Rollback方針）。
            assertEquals(remoteFilesBefore, allStoredFiles().size)
            assertFalse(
                device.database
                    .syncDao()
                    .getSettings()
                    ?.enabled == true,
            )
        }

    @Test
    fun remotePurgeDeletesEverythingAndReportsCompletion() =
        runBlocking {
            val device = device("端末A")
            device.addBook("work-a", "端末内の本")
            assertTrue(enableFirstDevice(device) is SyncActionResult.Success)
            assertTrue(allStoredFiles().isNotEmpty())

            val receipt = device.coordinator.purgeRemote()
            assertTrue(receipt.isSuccess)
            assertEquals(0, receipt.getOrNull()?.remainingObjectCount)
            assertNotNull(receipt.getOrNull()?.completedAtMillis)
            assertTrue(allStoredFiles().isEmpty())
            // local蔵書は残り、同期stateはresetされる。
            assertEquals(listOf("端末内の本"), device.titles())
            assertNull(device.identity())
        }

    @Test
    fun backendFailuresAreClassifiedAndRetriedWithinLimits() =
        runBlocking {
            val device = device("端末A")
            assertTrue(enableFirstDevice(device) is SyncActionResult.Success)
            device.addBook("work-a", "本")

            device.store.failWith = IOException("transient")
            val transient = device.coordinator.syncNow()
            assertTrue(transient is SyncActionResult.Failure)
            val transientFailure = (transient as SyncActionResult.Failure).failure
            assertEquals(SyncBackendErrorKind.IO_FAILURE, transientFailure.backendKind)
            assertTrue(transientFailure.retryable)
            // retryableな失敗は上限まで再試行する。
            assertTrue(device.store.failureCount >= 3)

            device.store.failWith = SecurityException("denied")
            device.store.failureCount = 0
            val permission = device.coordinator.syncNow()
            val permissionFailure = (permission as SyncActionResult.Failure).failure
            assertEquals(SyncBackendErrorKind.PERMISSION_LOST, permissionFailure.backendKind)
            assertFalse(permissionFailure.retryable)
            // retryしないため1回で終わる。
            assertEquals(1, device.store.failureCount)

            // 復旧後は同じoperationを重複なく送れる（冪等upload）。
            device.store.failWith = null
            assertTrue(device.coordinator.syncNow() is SyncActionResult.Success)
            assertEquals(0, device.engine.pendingOperations().size)
        }

    @Test
    fun tamperedRemoteObjectQuarantinesAndStopsSyncWithoutTouchingDomain() =
        runBlocking {
            val first = device("端末A")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)
            val second = device("端末B")
            joinSecondDevice(first, second)

            second.addBook("work-b", "改ざん対象")
            assertTrue(second.coordinator.syncNow() is SyncActionResult.Success)

            // backend上のobjectを改ざんする（悪意あるbackendを模す）。
            val target =
                allStoredFiles()
                    .filter { it.parentFile?.name == "objects" }
                    .maxByOrNull(File::lastModified)
            requireNotNull(target)
            val envelope =
                SyncWireJson.lenient.decodeFromString(WireSyncEnvelope.serializer(), target.readText())
            val flipped =
                Base64Url.decodeOrThrow(envelope.ciphertext).also {
                    it[10] = (it[10].toInt() xor 1).toByte()
                }
            target.writeText(
                SyncWireJson.strict.encodeToString(
                    WireSyncEnvelope.serializer(),
                    envelope.copy(ciphertext = Base64Url.encode(flipped)),
                ),
            )

            val titlesBefore = first.titles()
            val result = first.coordinator.syncNow()
            assertTrue("tamper must fail: $result", result is SyncActionResult.Failure)
            assertEquals(
                SyncFailureReason.SECURITY_LOCKOUT,
                (result as SyncActionResult.Failure).failure.reason,
            )
            // domainへ適用せず、quarantineへ保存して同期を停止する。
            assertEquals(titlesBefore, first.titles())
            assertTrue(first.database.syncKeyDao().countQuarantine() > 0)
            assertNotNull(requireNotNull(first.identity()).securityLockout)
            assertTrue(first.coordinator.syncNow() is SyncActionResult.Failure)
        }

    @Test
    fun localDataAndExportSurviveDisablingAndSwitchingBackends() =
        runBlocking {
            val device = device("端末A")
            device.addBook("work-a", "移行前の本")
            assertTrue(enableFirstDevice(device) is SyncActionResult.Success)
            val before = device.database.libraryDao().getAllWorks()

            device.coordinator.stopSync()
            assertEquals(before, device.database.libraryDao().getAllWorks())

            // 別フォルダ（別adapter構成）へ切り替えてもlocal dataは不変。
            val otherRoot = folder.newFolder("other-backend")
            device.factory.delegateRoot = otherRoot
            device.consent.grant(ConsentPurpose.LIBRARY_SYNC)
            assertTrue(
                device.coordinator.createLibrary(device.name, BACKEND_TYPE, "other") is
                    SyncActionResult.Success,
            )
            assertEquals(before, device.database.libraryDao().getAllWorks())
            assertEquals(listOf("移行前の本"), device.titles())
            assertTrue(otherRoot.walkTopDown().any { it.isFile })
        }

    @Test
    fun backupRestoreResetsSyncStateAndKeys() =
        runBlocking {
            val device = device("端末A")
            device.addBook("work-a", "復元対象")
            assertTrue(enableFirstDevice(device) is SyncActionResult.Success)
            assertNotNull(device.identity())

            device.engine.resetAfterDomainRestore()

            // 鍵state・registry cache・招待・quarantineを全消去し、再登録を要求する。
            val keyDao = device.database.syncKeyDao()
            assertNull(keyDao.getIdentity())
            assertTrue(keyDao.getPeerDevices().isEmpty())
            assertEquals(0, keyDao.countQuarantine())
            assertNull(keyDao.findWrappedKey("epoch", 1))
            val settings = requireNotNull(device.database.syncDao().getSettings())
            assertFalse(settings.enabled)
            assertNull(settings.deviceId)
            assertTrue(settings.requiresReregistration)
            // domain dataは保持される。
            assertEquals(listOf("復元対象"), device.titles())
        }

    @Test
    fun creatingALibraryTwiceInTheSameFolderIsRejected() =
        runBlocking {
            val first = device("端末A")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)

            val second = device("端末B")
            second.consent.grant(ConsentPurpose.LIBRARY_SYNC)
            val result = second.coordinator.createLibrary("端末B", BACKEND_TYPE, BACKEND_CONFIG)
            assertTrue(result is SyncActionResult.Failure)
            assertEquals(
                SyncFailureReason.LIBRARY_ALREADY_EXISTS,
                (result as SyncActionResult.Failure).failure.reason,
            )
            assertNull(second.identity())
        }

    @Test
    fun invalidOrReusedInviteIsRejected() =
        runBlocking {
            val first = device("端末A")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)

            val second = device("端末B")
            second.consent.grant(ConsentPurpose.LIBRARY_SYNC)
            val bogus = second.coordinator.joinLibrary("端末B", BACKEND_TYPE, BACKEND_CONFIG, "not-a-code")
            assertTrue(bogus is SyncActionResult.Failure)
            assertEquals(
                SyncFailureReason.INVITE_INVALID,
                (bogus as SyncActionResult.Failure).failure.reason,
            )

            joinSecondDevice(first, second)
            // 使用済み招待は2台目の承認へ再利用できない。
            val third = device("端末C")
            third.consent.grant(ConsentPurpose.LIBRARY_SYNC)
            val usedInvite = requireNotNull(first.coordinator.lastCreatedInvite)
            val reuse =
                third.coordinator.joinLibrary("端末C", BACKEND_TYPE, BACKEND_CONFIG, usedInvite.encode())
            assertTrue(reuse is SyncActionResult.JoinPending)
            assertTrue(first.coordinator.pendingJoinRequests().isEmpty())
        }

    @Test
    fun joinBeforeApprovalReportsNotReadyWithoutChangingLocalData() =
        runBlocking {
            val first = device("端末A")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)
            val second = device("端末B")
            second.addBook("work-local", "参加前の本")
            second.consent.grant(ConsentPurpose.LIBRARY_SYNC)
            assertTrue(first.coordinator.createInvite() is SyncActionResult.Success)
            val invite = requireNotNull(first.coordinator.lastCreatedInvite)
            assertTrue(
                second.coordinator.joinLibrary("端末B", BACKEND_TYPE, BACKEND_CONFIG, invite.encode()) is
                    SyncActionResult.JoinPending,
            )

            val premature = second.coordinator.completeJoin()
            assertTrue(premature is SyncActionResult.Failure)
            assertEquals(
                SyncFailureReason.JOIN_NOT_READY,
                (premature as SyncActionResult.Failure).failure.reason,
            )
            assertEquals(listOf("参加前の本"), second.titles())
            assertFalse(requireNotNull(second.identity()).activated)
        }

    @Test
    fun localOnlyBooksAreUploadedAfterJoining() =
        runBlocking {
            val first = device("端末A")
            first.addBook("work-a", "Aの本")
            assertTrue(enableFirstDevice(first) is SyncActionResult.Success)

            val second = device("端末B")
            second.addBook("work-local", "Bのローカル本")
            joinSecondDevice(first, second)

            // 参加時にsnapshotと自分のlocal本が両立する。
            assertEquals(listOf("Aの本", "Bのローカル本"), second.titles())
            assertTrue(first.coordinator.syncNow() is SyncActionResult.Success)
            assertEquals(listOf("Aの本", "Bのローカル本"), first.titles())
        }

    private fun allStoredFiles(): List<File> =
        folder.root
            .walkTopDown()
            .filter(File::isFile)
            .toList()

    // ---- Fakes ----

    private class MutableClock(
        private var current: Long = 1_753_920_000_000,
    ) {
        fun now(): Long = current.also { current += 1_000 }
    }

    private class RecordingScheduler : LibrarySyncScheduler {
        val calls = mutableListOf<Boolean>()

        override fun reconcile(enabled: Boolean) {
            calls += enabled
        }
    }

    private class FakeConsentRepository : ConsentRepository {
        private val state = MutableStateFlow<Map<ConsentPurpose, ConsentRecord>>(emptyMap())

        override fun observeConsents(): Flow<Map<ConsentPurpose, ConsentRecord>> = state

        override suspend fun isGranted(purpose: ConsentPurpose): Boolean = state.value[purpose]?.granted == true

        override suspend fun grant(purpose: ConsentPurpose): ConsentRecord {
            val record = ConsentRecord(purpose, purpose.policyVersion, 1_000, null)
            state.value = state.value + (purpose to record)
            return record
        }

        override suspend fun revoke(purpose: ConsentPurpose): ConsentRecord? {
            val record = ConsentRecord(purpose, purpose.policyVersion, 1_000, 2_000)
            state.value = state.value + (purpose to record)
            return record
        }
    }

    /** ファイルアクセス回数を数え、任意のIO失敗を注入できるstore。 */
    private class CountingObjectStore(
        var delegate: SyncObjectStore,
    ) : SyncObjectStore {
        var accessCount: Int = 0
            private set
        var failWith: Exception? = null
        var failureCount: Int = 0

        override fun list(directory: List<String>): List<String> = guard { delegate.list(directory) }

        override fun read(path: List<String>): ByteArray? = guard { delegate.read(path) }

        override fun writeIfAbsent(
            path: List<String>,
            bytes: ByteArray,
        ): Boolean = guard { delegate.writeIfAbsent(path, bytes) }

        override fun writeReplace(
            path: List<String>,
            bytes: ByteArray,
        ) = guard { delegate.writeReplace(path, bytes) }

        override fun delete(path: List<String>): Boolean = guard { delegate.delete(path) }

        override fun deleteRecursively(directory: List<String>): Int = guard { delegate.deleteRecursively(directory) }

        private fun <T> guard(block: () -> T): T {
            accessCount += 1
            failWith?.let { error ->
                failureCount += 1
                throw when (error) {
                    is SecurityException -> {
                        SyncBackendException(SyncBackendErrorKind.PERMISSION_LOST, "denied", error)
                    }

                    is IOException -> {
                        dev.ndcshelf.app.data.sync.backend
                            .classifyIo(error)
                    }

                    else -> {
                        SyncBackendException(SyncBackendErrorKind.IO_FAILURE, "failed", error)
                    }
                }
            }
            return block()
        }
    }

    private class TestBackendFactory(
        private val store: CountingObjectStore,
        private val clock: MutableClock,
    ) : SyncBackendFactory {
        var createCount: Int = 0
            private set
        var delegateRoot: File? = null

        override fun create(
            backendType: String,
            backendConfig: String,
            libraryOpaqueId: String,
        ): SyncBackend {
            createCount += 1
            delegateRoot?.let { store.delegate = FileSyncObjectStore(it) }
            return FolderSyncBackend(
                store,
                libraryOpaqueId,
                nowMillis = clock::now,
                dispatcher = Dispatchers.Unconfined,
            )
        }

        override suspend fun discoverLibrary(
            backendType: String,
            backendConfig: String,
        ): String? {
            createCount += 1
            delegateRoot?.let { store.delegate = FileSyncObjectStore(it) }
            return FolderSyncBackend.discoverLibrary(store)
        }
    }

    private companion object {
        const val BACKEND_TYPE = "saf-folder"
        const val BACKEND_CONFIG = "content://test/tree"
    }
}
