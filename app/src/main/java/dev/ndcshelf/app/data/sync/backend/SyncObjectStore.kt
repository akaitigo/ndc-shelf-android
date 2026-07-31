package dev.ndcshelf.app.data.sync.backend

import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncBackendException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * FolderSyncBackendの保存先抽象。pathはlibrary rootからのsegment列で、
 * 実装は失敗をSyncBackendExceptionへ分類する。E2EE ciphertextだけを扱い、
 * 内容を解釈しない。
 */
interface SyncObjectStore {
    /** directory直下のfile名一覧。directoryが無ければ空。 */
    fun list(directory: List<String>): List<String>

    /** fileの全bytes。無ければnull。 */
    fun read(path: List<String>): ByteArray?

    /** 存在しない場合だけ書き込む。既存ならfalse。 */
    fun writeIfAbsent(
        path: List<String>,
        bytes: ByteArray,
    ): Boolean

    /** 既存を置き換える書き込み。 */
    fun writeReplace(
        path: List<String>,
        bytes: ByteArray,
    )

    fun delete(path: List<String>): Boolean

    /** directory以下を全削除し、削除できず残った項目数を返す。 */
    fun deleteRecursively(directory: List<String>): Int
}

/** java.io.File実装。JVMテストと将来のlocal folder用。 */
class FileSyncObjectStore(
    private val root: File,
) : SyncObjectStore {
    override fun list(directory: List<String>): List<String> =
        classify {
            val dir = resolve(directory)
            if (!dir.isDirectory) emptyList() else dir.list()?.sorted().orEmpty()
        }

    override fun read(path: List<String>): ByteArray? =
        classify {
            val file = resolve(path)
            if (!file.isFile) null else file.readBytes()
        }

    override fun writeIfAbsent(
        path: List<String>,
        bytes: ByteArray,
    ): Boolean =
        classify {
            val file = resolve(path)
            file.parentFile?.mkdirs()
            if (!file.createNewFile()) return@classify false
            try {
                file.writeBytes(bytes)
                true
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    override fun writeReplace(
        path: List<String>,
        bytes: ByteArray,
    ): Unit =
        classify {
            val file = resolve(path)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.tmp")
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(file)) {
                    file.delete()
                    check(temp.renameTo(file)) { "Could not commit a sync file replacement." }
                }
            } finally {
                temp.delete()
            }
        }

    override fun delete(path: List<String>): Boolean = classify { resolve(path).delete() }

    override fun deleteRecursively(directory: List<String>): Int =
        classify {
            val dir = resolve(directory)
            if (!dir.exists()) return@classify 0
            dir.deleteRecursively()
            countRemaining(dir)
        }

    private fun countRemaining(file: File): Int {
        if (!file.exists()) return 0
        if (file.isFile) return 1
        return 1 + (file.listFiles()?.sumOf(::countRemaining) ?: 0)
    }

    private fun resolve(path: List<String>): File {
        require(path.all(::isSafeSegment)) { "Unsafe sync path segment." }
        return path.fold(root) { parent, segment -> File(parent, segment) }
    }

    private inline fun <T> classify(block: () -> T): T =
        try {
            block()
        } catch (error: SecurityException) {
            throw SyncBackendException(
                SyncBackendErrorKind.PERMISSION_LOST,
                "Folder access was denied.",
                error,
            )
        } catch (error: FileNotFoundException) {
            throw SyncBackendException(
                SyncBackendErrorKind.NOT_FOUND,
                "A sync file disappeared during access.",
                error,
            )
        } catch (error: InterruptedIOException) {
            throw SyncBackendException(SyncBackendErrorKind.IO_FAILURE, "Folder IO was interrupted.", error)
        } catch (error: IOException) {
            throw classifyIo(error)
        } catch (error: IllegalStateException) {
            throw SyncBackendException(SyncBackendErrorKind.IO_FAILURE, "Folder IO failed.", error)
        }
}

internal fun classifyIo(error: IOException): SyncBackendException {
    val message = error.message.orEmpty()
    val kind =
        if (message.contains("No space left", ignoreCase = true) ||
            message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("Disk full", ignoreCase = true)
        ) {
            SyncBackendErrorKind.STORAGE_FULL
        } else if (message.contains("Permission denied", ignoreCase = true) ||
            message.contains("EACCES", ignoreCase = true)
        ) {
            SyncBackendErrorKind.PERMISSION_LOST
        } else {
            SyncBackendErrorKind.IO_FAILURE
        }
    return SyncBackendException(kind, "Folder IO failed.", error)
}

internal fun isSafeSegment(segment: String): Boolean =
    segment.isNotBlank() &&
        segment.length <= 200 &&
        segment != "." &&
        segment != ".." &&
        segment.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
