package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncKeyDao {
    @Query("SELECT * FROM sync_identity WHERE id = 1")
    suspend fun getIdentity(): SyncIdentityEntity?

    @Query("SELECT * FROM sync_identity WHERE id = 1")
    fun observeIdentity(): Flow<SyncIdentityEntity?>

    @Upsert
    suspend fun upsertIdentity(identity: SyncIdentityEntity)

    /** encryptionCounterをtransactionalに1増やす。上限到達では増やさない。 */
    @Query(
        "UPDATE sync_identity SET encryptionCounter = encryptionCounter + 1 " +
            "WHERE id = 1 AND encryptionCounter < 9223372036854775807",
    )
    suspend fun incrementEncryptionCounter(): Int

    @Query("SELECT encryptionCounter FROM sync_identity WHERE id = 1")
    suspend fun getEncryptionCounter(): Long?

    @Query("DELETE FROM sync_identity")
    suspend fun deleteIdentity()

    @Query("SELECT * FROM sync_wrapped_keys WHERE keyType = :keyType AND keyVersion = :keyVersion")
    suspend fun findWrappedKey(
        keyType: String,
        keyVersion: Int,
    ): SyncWrappedKeyEntity?

    @Upsert
    suspend fun upsertWrappedKey(key: SyncWrappedKeyEntity)

    @Query("DELETE FROM sync_wrapped_keys")
    suspend fun deleteWrappedKeys()

    @Query("SELECT * FROM sync_peer_devices ORDER BY isSelf DESC, addedAtGeneration, deviceId")
    suspend fun getPeerDevices(): List<SyncPeerDeviceEntity>

    @Query("SELECT * FROM sync_peer_devices ORDER BY isSelf DESC, addedAtGeneration, deviceId")
    fun observePeerDevices(): Flow<List<SyncPeerDeviceEntity>>

    @Query("SELECT * FROM sync_peer_devices WHERE deviceId = :deviceId")
    suspend fun findPeerDevice(deviceId: String): SyncPeerDeviceEntity?

    @Upsert
    suspend fun upsertPeerDevices(devices: List<SyncPeerDeviceEntity>)

    @Query("DELETE FROM sync_peer_devices WHERE deviceId NOT IN (:deviceIds)")
    suspend fun deletePeerDevicesNotIn(deviceIds: List<String>)

    @Query("DELETE FROM sync_peer_devices")
    suspend fun deletePeerDevices()

    @Query("SELECT * FROM sync_invites WHERE nonce = :nonce")
    suspend fun findInvite(nonce: String): SyncInviteEntity?

    @Query(
        "SELECT * FROM sync_invites WHERE consumedAt IS NULL AND expiresAt >= :nowMillis " +
            "ORDER BY createdAt DESC",
    )
    suspend fun findUnconsumedInvites(nowMillis: Long): List<SyncInviteEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInvite(invite: SyncInviteEntity)

    /** 未消費の招待だけをatomicに消費する。戻り値1で成功。 */
    @Query(
        "UPDATE sync_invites SET consumedAt = :nowMillis " +
            "WHERE nonce = :nonce AND consumedAt IS NULL AND expiresAt >= :nowMillis",
    )
    suspend fun consumeInvite(
        nonce: String,
        nowMillis: Long,
    ): Int

    @Query("DELETE FROM sync_invites WHERE expiresAt < :nowMillis")
    suspend fun deleteExpiredInvites(nowMillis: Long)

    @Query("DELETE FROM sync_invites")
    suspend fun deleteInvites()

    @Query("SELECT * FROM sync_processed_envelopes WHERE envelopeId = :envelopeId")
    suspend fun findProcessedEnvelope(envelopeId: String): SyncProcessedEnvelopeEntity?

    @Query("SELECT COUNT(*) FROM sync_processed_envelopes WHERE inviteNonce = :nonce")
    suspend fun countProcessedEnvelopesWithNonce(nonce: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProcessedEnvelope(envelope: SyncProcessedEnvelopeEntity): Long

    @Query("DELETE FROM sync_processed_envelopes")
    suspend fun deleteProcessedEnvelopes()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertQuarantine(entry: SyncQuarantineEntity): Long

    @Query("SELECT COUNT(*) FROM sync_quarantine")
    suspend fun countQuarantine(): Int

    @Query("SELECT * FROM sync_quarantine ORDER BY receivedAt")
    suspend fun getQuarantine(): List<SyncQuarantineEntity>

    @Query("DELETE FROM sync_quarantine")
    suspend fun deleteQuarantine()
}
