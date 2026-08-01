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
     * 導入済みモデルの全バイトを再ハッシュして台帳と照合する。不一致なら削除し、
     * 以後[installedFile]がnullを返すようにする。数百MiB〜数GiBを読むため
     * main threadから呼ばず、ロード前に1度だけ実行する。
     */
    fun verifyInstalled(definition: LlmModelDefinition): Boolean

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

/**
 * 導入失敗の分類。UI文言はstrings.xmlで対応付ける。
 *
 * 端末条件と空き容量の不足は導入を開始する前に[LlmCapabilityChecker]が弾くため、
 * ここには現れない。利用者のキャンセルは[kotlinx.coroutines.CancellationException]
 * として伝播し、失敗としては扱わない。
 */
enum class LlmModelInstallFailure {
    /** 取得元へ到達できない・通信失敗・許可外URLの拒否。 */
    TRANSPORT,

    /** 期待サイズと異なる（上限超過を含む）。 */
    SIZE_MISMATCH,

    /** SHA-256不一致。 */
    CHECKSUM_MISMATCH,

    /** 端末内への書き込みに失敗。 */
    STORAGE_ERROR,
}
