package dev.ndcshelf.app.domain.ai.llm

import java.io.File
import java.io.InputStream

/**
 * 端末内のモデル配置。アプリ専用領域だけを使い、導入は原子的に行う。
 *
 * 不変条件:
 * - 検証（サイズ・SHA-256）に成功したファイルだけを有効化する
 * - 有効化まで既存の検証済みモデルを保持し、失敗しても直前の状態へ戻る
 * - 中途半端な一時ファイルは有効な場所へ現れない
 */
interface LlmModelStore {
    fun state(definition: LlmModelDefinition): LlmModelState

    /** 検証済みモデルの実体。未導入・破損ならnull。 */
    fun installedFile(definition: LlmModelDefinition): File?

    /**
     * [source]から読み出して検証し、成功した場合だけ有効化する。
     * 呼び出し側はmain threadで呼ばない。cancelはcoroutineのキャンセルで行う。
     */
    suspend fun install(
        definition: LlmModelDefinition,
        source: LlmModelSource,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): LlmModelInstallResult

    /** 指定モデルと由来cacheを削除する。 */
    fun delete(definition: LlmModelDefinition): Boolean

    /** 全モデルとモデル由来cacheを削除する。削除後に断片が残らないこと。 */
    fun deleteAll(): Boolean
}

/** 導入元。ネットワークとSAFのどちらでも同じ検証を通す。 */
fun interface LlmModelSource {
    /** 読み出し用streamを開く。呼び出し側がcloseする。 */
    suspend fun open(): InputStream
}

sealed interface LlmModelState {
    data object NotInstalled : LlmModelState

    /** 導入中（一時ファイルが存在する）。有効なモデルとしては扱わない。 */
    data class Installing(
        val bytesWritten: Long,
        val totalBytes: Long,
    ) : LlmModelState

    data class Installed(
        val definition: LlmModelDefinition,
        val fileSizeBytes: Long,
        val installedAtMillis: Long,
    ) : LlmModelState
}

sealed interface LlmModelInstallResult {
    data class Installed(
        val file: File,
        val fileSizeBytes: Long,
    ) : LlmModelInstallResult

    data class Failed(
        val reason: LlmModelInstallFailure,
    ) : LlmModelInstallResult
}

/** 導入失敗の分類。UI文言はstrings.xmlで対応付ける。 */
enum class LlmModelInstallFailure {
    /** 端末条件を満たさない。 */
    DEVICE_UNSUPPORTED,

    /** 空き容量不足。 */
    INSUFFICIENT_STORAGE,

    /** 取得元へ到達できない・通信失敗。 */
    TRANSPORT,

    /** 許可外のURL・redirect・不正なhost。 */
    BLOCKED_SOURCE,

    /** 期待サイズと異なる（上限超過を含む）。 */
    SIZE_MISMATCH,

    /** SHA-256不一致。 */
    CHECKSUM_MISMATCH,

    /** 端末内への書き込みに失敗。 */
    STORAGE_ERROR,

    /** 利用者によるキャンセル。 */
    CANCELLED,
}
