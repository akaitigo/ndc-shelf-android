package dev.ndcshelf.app.data.local

import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallFailure
import dev.ndcshelf.app.domain.ai.llm.LlmModelInstallResult
import dev.ndcshelf.app.domain.ai.llm.LlmModelSource
import dev.ndcshelf.app.domain.ai.llm.LlmModelState
import dev.ndcshelf.app.domain.ai.llm.LlmModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * アプリ専用領域へモデルを配置するファイル実装。
 *
 * 配置:
 * ```
 * <root>/<modelId>/<version>/<fileName>   検証済みモデル
 * <root>/<modelId>/<version>/model.meta   検証結果（sha256・サイズ・導入時刻）
 * <root>/.staging/<uuid>.part             検証前の一時ファイル
 * ```
 *
 * 導入は「一時領域へ書き出す → 検証 → renameで有効化 → 旧versionを削除」の順で、
 * 検証に失敗した場合は一時ファイルだけを消し、既存の検証済みモデルを保持する。
 *
 * [root]には`Context.noBackupFilesDir`配下を渡すこと（OSクラウドbackup・D2D対象外）。
 */
class FileLlmModelStore(
    private val root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LlmModelStore {
    override fun state(definition: LlmModelDefinition): LlmModelState {
        val meta = readMeta(definition) ?: return LlmModelState.NotInstalled
        val file = modelFile(definition)
        if (!file.isFile || file.length() != definition.sizeBytes || meta.sha256 != definition.sha256) {
            return LlmModelState.NotInstalled
        }
        return LlmModelState.Installed(
            definition = definition,
            fileSizeBytes = file.length(),
            installedAtMillis = meta.installedAtMillis,
        )
    }

    override fun installedFile(definition: LlmModelDefinition): File? =
        when (state(definition)) {
            is LlmModelState.Installed -> modelFile(definition)
            else -> null
        }

    override suspend fun install(
        definition: LlmModelDefinition,
        source: LlmModelSource,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit,
    ): LlmModelInstallResult {
        val staging = File(root, STAGING_DIRECTORY)
        // mkdirs()は並行して同じディレクトリが作られた場合もfalseを返すため、結果ではなく実体で判定する。
        staging.mkdirs()
        if (!staging.isDirectory) {
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)
        }
        val temporary = File(staging, "${UUID.randomUUID()}$STAGING_SUFFIX")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            source.open().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        written += read
                        if (written > definition.sizeBytes) {
                            temporary.delete()
                            return LlmModelInstallResult.Failed(LlmModelInstallFailure.SIZE_MISMATCH)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(written, definition.sizeBytes)
                    }
                    output.flush()
                }
            }
        } catch (cancellation: CancellationException) {
            temporary.delete()
            throw cancellation
        } catch (_: IOException) {
            temporary.delete()
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.TRANSPORT)
        } catch (_: Exception) {
            temporary.delete()
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)
        }

        if (written != definition.sizeBytes) {
            temporary.delete()
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.SIZE_MISMATCH)
        }
        val actualSha256 = digest.digest().toHex()
        if (actualSha256 != definition.sha256) {
            temporary.delete()
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.CHECKSUM_MISMATCH)
        }

        val target = modelFile(definition)
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        if (parent == null || !parent.isDirectory) {
            temporary.delete()
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)
        }
        // 有効化は同一ファイルシステム上のrenameだけで行う。Linuxのrename(2)は既存の
        // 宛先を不可分に置換するため事前削除しない（renameが失敗しても旧モデルが残る）。
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)
        }
        val metaWritten =
            runCatching {
                metaFile(definition).writeText(
                    listOf(
                        META_VERSION,
                        definition.sha256,
                        definition.sizeBytes.toString(),
                        nowMillis().toString(),
                    ).joinToString("\n"),
                )
            }.isSuccess
        if (!metaWritten) {
            // metaが無ければstate()はNotInstalledになる。ロードされない状態で残さず消す。
            target.delete()
            metaFile(definition).delete()
            return LlmModelInstallResult.Failed(LlmModelInstallFailure.STORAGE_ERROR)
        }
        // 成功が確定してから旧versionを片付ける。
        removeOtherVersions(definition)
        return LlmModelInstallResult.Installed(file = target, fileSizeBytes = definition.sizeBytes)
    }

    /**
     * 導入済みモデルのSHA-256を再計算して台帳と照合する。
     * 不一致なら削除し、以後ロードできない状態にする。
     */
    override fun verifyInstalled(definition: LlmModelDefinition): Boolean {
        val file = modelFile(definition)
        if (!file.isFile || file.length() != definition.sizeBytes) {
            delete(definition)
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val matches =
            runCatching {
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().toHex() == definition.sha256
            }.getOrDefault(false)
        if (!matches) delete(definition)
        return matches
    }

    override fun delete(definition: LlmModelDefinition): Boolean =
        runCatching { File(root, definition.id).deleteRecursively() }.getOrDefault(false)

    override fun deleteAll(): Boolean =
        runCatching {
            if (!root.exists()) true else root.deleteRecursively()
        }.getOrDefault(false)

    private fun modelFile(definition: LlmModelDefinition): File =
        File(File(File(root, definition.id), definition.version), definition.fileName)

    private fun metaFile(definition: LlmModelDefinition): File = File(File(File(root, definition.id), definition.version), META_FILE_NAME)

    private fun readMeta(definition: LlmModelDefinition): ModelMeta? =
        runCatching {
            val lines = metaFile(definition).readLines()
            if (lines.size < 4 || lines[0] != META_VERSION) return@runCatching null
            ModelMeta(
                sha256 = lines[1],
                sizeBytes = lines[2].toLong(),
                installedAtMillis = lines[3].toLong(),
            )
        }.getOrNull()

    private fun removeOtherVersions(definition: LlmModelDefinition) {
        runCatching {
            File(root, definition.id)
                .listFiles()
                .orEmpty()
                .filter { candidate -> candidate.isDirectory && candidate.name != definition.version }
                .forEach { candidate -> candidate.deleteRecursively() }
            File(root, STAGING_DIRECTORY).listFiles().orEmpty().forEach { file -> file.delete() }
        }
    }

    private data class ModelMeta(
        val sha256: String,
        val sizeBytes: Long,
        val installedAtMillis: Long,
    )

    private fun ByteArray.toHex(): String = joinToString("") { byte -> String.format(Locale.US, "%02x", byte) }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val STAGING_DIRECTORY = ".staging"
        const val STAGING_SUFFIX = ".part"
        const val META_FILE_NAME = "model.meta"
        const val META_VERSION = "ndc-shelf-llm-model/1"
    }
}
