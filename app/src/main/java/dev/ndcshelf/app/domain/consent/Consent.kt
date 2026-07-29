package dev.ndcshelf.app.domain.consent

import kotlinx.coroutines.flow.Flow

/**
 * 外部通信を行う任意機能の目的別同意。全機能で既定OFFとし、包括同意は
 * 存在しない。policyVersionを上げた目的は再同意されるまで未同意として扱う。
 */
enum class ConsentPurpose(
    val policyVersion: Int,
) {
    /** 新刊ウォッチ: 有効化したシリーズ名をNDL Searchへ検索クエリとして送信する。 */
    SERIES_RELEASE_WATCH(1),

    /** 任意同期: E2EE暗号化済みoperationを同期backendへ送信する（#38で有効化）。 */
    LIBRARY_SYNC(1),

    /** 自然言語検索のクラウドモード（#40で有効化）。 */
    NATURAL_LANGUAGE_SEARCH(1),

    /** オプトインAI司書（#42で有効化）。 */
    AI_LIBRARIAN(1),
}

data class ConsentRecord(
    val purpose: ConsentPurpose,
    val consentedVersion: Int,
    val grantedAtMillis: Long?,
    val revokedAtMillis: Long?,
) {
    /** 現行versionへ同意済みで、撤回されていない場合だけ有効。 */
    val granted: Boolean
        get() =
            grantedAtMillis != null &&
                revokedAtMillis == null &&
                consentedVersion >= purpose.policyVersion
}

interface ConsentRepository {
    fun observeConsents(): Flow<Map<ConsentPurpose, ConsentRecord>>

    suspend fun isGranted(purpose: ConsentPurpose): Boolean

    suspend fun grant(purpose: ConsentPurpose): ConsentRecord

    suspend fun revoke(purpose: ConsentPurpose): ConsentRecord?
}
