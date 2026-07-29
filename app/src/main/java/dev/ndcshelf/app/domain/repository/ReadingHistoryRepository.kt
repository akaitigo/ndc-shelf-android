package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.model.ReadingSession
import dev.ndcshelf.app.domain.model.ReadingSessionDraft
import dev.ndcshelf.app.domain.model.ReadingSessionValidationError
import kotlinx.coroutines.flow.Flow

/**
 * 読書セッション履歴の契約。
 *
 * ReadingStatus整合規則（コードで強制する）:
 * - 履歴の追加・編集・削除のたびに、対象コピーの現在の readingStatus を履歴から導出して更新する。
 *   READING のセッションがあれば READING、なければ PAUSED があれば PAUSED、
 *   なければ FINISHED があれば READ とする。
 * - 履歴が0件のコピーは readingStatus を変更しない（手動設定を尊重する）。
 * - コピーの readingStatus を直接編集しても履歴を自動生成しない（推測で履歴を作らない）。
 * - 進行中（READING / PAUSED）のセッションはコピーごとに最大1件とする。
 */
interface ReadingHistoryRepository {
    fun observeSessionsForEdition(editionId: String): Flow<List<ReadingSession>>

    suspend fun addSession(
        copyId: String,
        draft: ReadingSessionDraft,
    ): AddReadingSessionResult

    suspend fun updateSession(
        sessionId: String,
        draft: ReadingSessionDraft,
    ): UpdateReadingSessionResult

    suspend fun deleteSession(sessionId: String): DeleteReadingSessionResult

    /** 削除直後の取り消し。既存IDと衝突する場合は上書きせず競合として拒否する。 */
    suspend fun restoreSession(session: ReadingSession): RestoreReadingSessionResult
}

sealed interface AddReadingSessionResult {
    data class Added(
        val session: ReadingSession,
    ) : AddReadingSessionResult

    data class Invalid(
        val errors: List<ReadingSessionValidationError>,
    ) : AddReadingSessionResult

    /** 同一内容のセッションが既に存在する（重複イベント）。 */
    data object Duplicate : AddReadingSessionResult

    /** 進行中のセッションが既に存在する。 */
    data object ActiveSessionExists : AddReadingSessionResult

    data object CopyNotFound : AddReadingSessionResult

    data object Failure : AddReadingSessionResult
}

sealed interface UpdateReadingSessionResult {
    data class Updated(
        val previous: ReadingSession,
        val current: ReadingSession,
    ) : UpdateReadingSessionResult

    data class Invalid(
        val errors: List<ReadingSessionValidationError>,
    ) : UpdateReadingSessionResult

    data object Duplicate : UpdateReadingSessionResult

    data object ActiveSessionExists : UpdateReadingSessionResult

    data object NotFound : UpdateReadingSessionResult

    data object Failure : UpdateReadingSessionResult
}

sealed interface DeleteReadingSessionResult {
    data class Deleted(
        val session: ReadingSession,
    ) : DeleteReadingSessionResult

    data object NotFound : DeleteReadingSessionResult

    data object Failure : DeleteReadingSessionResult
}

sealed interface RestoreReadingSessionResult {
    data object Restored : RestoreReadingSessionResult

    data object Conflict : RestoreReadingSessionResult

    data object Failure : RestoreReadingSessionResult
}
