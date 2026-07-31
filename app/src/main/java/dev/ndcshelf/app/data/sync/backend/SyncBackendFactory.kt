package dev.ndcshelf.app.data.sync.backend

import android.content.Context
import android.net.Uri
import dev.ndcshelf.app.data.local.SyncIdentityEntity
import dev.ndcshelf.app.domain.sync.SyncBackend
import dev.ndcshelf.app.domain.sync.SyncBackendErrorKind
import dev.ndcshelf.app.domain.sync.SyncBackendException

/**
 * 保存済み構成からbackend adapterを組み立てる。coordinatorはこの抽象
 * だけへ依存し、adapter実装（SAF・将来のhosted backend）と分離する。
 */
interface SyncBackendFactory {
    fun create(
        backendType: String,
        backendConfig: String,
        libraryOpaqueId: String,
    ): SyncBackend

    /** 保存先に既存libraryがあればそのopaqueIdを返す（join用）。 */
    suspend fun discoverLibrary(
        backendType: String,
        backendConfig: String,
    ): String?
}

class AndroidSyncBackendFactory(
    private val context: Context,
) : SyncBackendFactory {
    override fun create(
        backendType: String,
        backendConfig: String,
        libraryOpaqueId: String,
    ): SyncBackend =
        FolderSyncBackend(
            store = storeFor(backendType, backendConfig),
            libraryOpaqueId = libraryOpaqueId,
        )

    override suspend fun discoverLibrary(
        backendType: String,
        backendConfig: String,
    ): String? = FolderSyncBackend.discoverLibrary(storeFor(backendType, backendConfig))

    private fun storeFor(
        backendType: String,
        backendConfig: String,
    ): SyncObjectStore {
        if (backendType != SyncIdentityEntity.BACKEND_SAF_FOLDER) {
            throw SyncBackendException(
                SyncBackendErrorKind.INCOMPATIBLE_CAPABILITY,
                "Unknown sync backend type.",
            )
        }
        val treeUri = Uri.parse(backendConfig)
        val hasPermission =
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == treeUri && permission.isReadPermission && permission.isWritePermission
            }
        if (!hasPermission) {
            throw SyncBackendException(
                SyncBackendErrorKind.PERMISSION_LOST,
                "The selected folder permission was lost.",
            )
        }
        return SafSyncObjectStore(context, treeUri)
    }
}
