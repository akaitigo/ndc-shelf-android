package dev.ndcshelf.app.data.sync.backend

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncBackendException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Storage Access Framework実装。利用者が選択したtree URI配下だけへ
 * 暗号化済みbytesを保存する。権限喪失・容量不足・IO失敗を分類し、
 * account認証・network通信を行わない。
 */
class SafSyncObjectStore(
    private val context: Context,
    private val treeUri: Uri,
) : SyncObjectStore {
    override fun list(directory: List<String>): List<String> =
        classify {
            val dir = resolveDirectory(directory, create = false) ?: return@classify emptyList()
            dir
                .listFiles()
                .mapNotNull { file -> file.name }
                .sorted()
        }

    override fun read(path: List<String>): ByteArray? =
        classify {
            val file = resolveFile(path) ?: return@classify null
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: throw IOException("Could not open a sync file for reading.")
        }

    override fun writeIfAbsent(
        path: List<String>,
        bytes: ByteArray,
    ): Boolean =
        classify {
            if (resolveFile(path) != null) return@classify false
            writeNewFile(path, bytes)
            true
        }

    override fun writeReplace(
        path: List<String>,
        bytes: ByteArray,
    ): Unit =
        classify {
            val existing = resolveFile(path)
            if (existing == null) {
                writeNewFile(path, bytes)
                return@classify
            }
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("Could not open a sync file for writing.")
        }

    override fun delete(path: List<String>): Boolean =
        classify {
            resolveFile(path)?.delete() == true
        }

    override fun deleteRecursively(directory: List<String>): Int =
        classify {
            val dir = resolveDirectory(directory, create = false) ?: return@classify 0
            dir.delete()
            resolveDirectory(directory, create = false)?.let(::countEntries) ?: 0
        }

    private fun countEntries(file: DocumentFile): Int = if (file.isDirectory) 1 + file.listFiles().sumOf(::countEntries) else 1

    private fun writeNewFile(
        path: List<String>,
        bytes: ByteArray,
    ) {
        val directory =
            resolveDirectory(path.dropLast(1), create = true)
                ?: throw IOException("Could not create a sync directory.")
        val name = path.last()
        require(isSafeSegment(name)) { "Unsafe sync path segment." }
        val file =
            directory.createFile("application/octet-stream", name)
                ?: throw IOException("Could not create a sync file.")
        // providerが拡張子や重複名でrenameした場合は作り直さず失敗させる。
        if (file.name != name) {
            file.delete()
            throw IOException("The document provider renamed a sync file.")
        }
        try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("Could not open a sync file for writing.")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw SyncBackendException(
                SyncBackendErrorKind.PERMISSION_LOST,
                "The selected folder is no longer accessible.",
            )

    private fun resolveDirectory(
        path: List<String>,
        create: Boolean,
    ): DocumentFile? {
        var current = root()
        path.forEach { segment ->
            require(isSafeSegment(segment)) { "Unsafe sync path segment." }
            val next = current.findFile(segment)
            current =
                when {
                    next != null && next.isDirectory -> {
                        next
                    }

                    next != null -> {
                        throw IOException("A sync directory name is occupied by a file.")
                    }

                    create -> {
                        current.createDirectory(segment)
                            ?: throw IOException("Could not create a sync directory.")
                    }

                    else -> {
                        return null
                    }
                }
        }
        return current
    }

    private fun resolveFile(path: List<String>): DocumentFile? {
        val directory = resolveDirectory(path.dropLast(1), create = false) ?: return null
        require(isSafeSegment(path.last())) { "Unsafe sync path segment." }
        val file = directory.findFile(path.last()) ?: return null
        return if (file.isFile) file else null
    }

    private inline fun <T> classify(block: () -> T): T =
        try {
            block()
        } catch (error: SyncBackendException) {
            throw error
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
        } catch (error: IOException) {
            throw classifyIo(error)
        } catch (error: IllegalStateException) {
            throw SyncBackendException(SyncBackendErrorKind.IO_FAILURE, "Folder IO failed.", error)
        }
}
