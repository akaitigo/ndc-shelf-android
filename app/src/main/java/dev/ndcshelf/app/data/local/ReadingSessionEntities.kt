package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 読書セッション（開始・中断・再開・読了・再読）の履歴。
 *
 * - `id` は端末間で衝突しない独立UUIDで、同期時の識別子として使う。
 * - `startedDay` / `finishedDay` は部分日付（"2026" / "2026-07" / "2026-07-29"）を
 *   ローカル暦日としてTEXT保存し、時刻・タイムゾーンを持たない。
 * - コピー削除時はCASCADEで履歴も削除する（同期へは明示的なdeleteを記録する）。
 */
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = OwnedCopyEntity::class,
            parentColumns = ["id"],
            childColumns = ["copyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["copyId"])],
)
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val copyId: String,
    val status: String,
    val startedDay: String?,
    val finishedDay: String?,
    val rating: Int?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ReadingSessionRow(
    val id: String,
    val copyId: String,
    val copyLabel: String,
    val status: String,
    val startedDay: String?,
    val finishedDay: String?,
    val rating: Int?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_sessions ORDER BY id")
    suspend fun getAll(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun findById(sessionId: String): ReadingSessionEntity?

    @Query(
        "SELECT * FROM reading_sessions WHERE copyId = :copyId ORDER BY createdAt ASC, id ASC",
    )
    suspend fun findByCopyId(copyId: String): List<ReadingSessionEntity>

    @Query(
        """
        SELECT
            sessions.id AS id,
            sessions.copyId AS copyId,
            copies.copyLabel AS copyLabel,
            sessions.status AS status,
            sessions.startedDay AS startedDay,
            sessions.finishedDay AS finishedDay,
            sessions.rating AS rating,
            sessions.note AS note,
            sessions.createdAt AS createdAt,
            sessions.updatedAt AS updatedAt
        FROM reading_sessions AS sessions
        INNER JOIN owned_copies AS copies ON copies.id = sessions.copyId
        WHERE copies.editionId = :editionId
        ORDER BY sessions.createdAt DESC, sessions.id ASC
        """,
    )
    fun observeSessionsForEdition(editionId: String): Flow<List<ReadingSessionRow>>

    @Query(
        """
        SELECT
            sessions.id AS id,
            sessions.copyId AS copyId,
            copies.copyLabel AS copyLabel,
            sessions.status AS status,
            sessions.startedDay AS startedDay,
            sessions.finishedDay AS finishedDay,
            sessions.rating AS rating,
            sessions.note AS note,
            sessions.createdAt AS createdAt,
            sessions.updatedAt AS updatedAt
        FROM reading_sessions AS sessions
        INNER JOIN owned_copies AS copies ON copies.id = sessions.copyId
        ORDER BY sessions.createdAt DESC, sessions.id ASC
        """,
    )
    fun observeAllSessions(): Flow<List<ReadingSessionRow>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ReadingSessionEntity)

    @Upsert
    suspend fun upsert(session: ReadingSessionEntity)

    @Upsert
    suspend fun upsertAll(sessions: List<ReadingSessionEntity>)

    @Query("DELETE FROM reading_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: String): Int

    @Query("DELETE FROM reading_sessions")
    suspend fun deleteAll()

    @Query("UPDATE owned_copies SET readingStatus = :readingStatus WHERE id = :copyId")
    suspend fun updateCopyReadingStatus(
        copyId: String,
        readingStatus: String,
    ): Int
}
