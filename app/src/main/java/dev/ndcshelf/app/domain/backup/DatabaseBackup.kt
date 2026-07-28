package dev.ndcshelf.app.domain.backup

import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import dev.ndcshelf.app.data.local.ScanAttemptEntity
import dev.ndcshelf.app.data.local.ScanSessionEntity
import dev.ndcshelf.app.data.local.WishlistItemEntity

data class DatabaseSnapshot(
    val works: List<BookWorkEntity>,
    val editions: List<BookEditionEntity>,
    val copies: List<OwnedCopyEntity>,
    val rooms: List<LocationRoomEntity> = emptyList(),
    val shelves: List<LocationShelfEntity> = emptyList(),
    val tiers: List<LocationTierEntity> = emptyList(),
    val wishlistItems: List<WishlistItemEntity> = emptyList(),
    val scanSessions: List<ScanSessionEntity> = emptyList(),
    val scanAttempts: List<ScanAttemptEntity> = emptyList(),
)

data class DatabaseBackupMetadata(
    val formatVersion: Int,
    val databaseVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val workCount: Int,
    val editionCount: Int,
    val copyCount: Int,
    val wishlistCount: Int = 0,
    val scanSessionCount: Int = 0,
    val scanAttemptCount: Int = 0,
)

class DatabaseBackupPreview internal constructor(
    val metadata: DatabaseBackupMetadata,
    internal val snapshot: DatabaseSnapshot,
)

sealed interface DatabaseBackupCreateResult {
    data class Success(val metadata: DatabaseBackupMetadata) : DatabaseBackupCreateResult
    data class Failure(val reason: DatabaseBackupFailure) : DatabaseBackupCreateResult
}

sealed interface DatabaseBackupInspectResult {
    data class Valid(val preview: DatabaseBackupPreview) : DatabaseBackupInspectResult
    data class Invalid(val reason: DatabaseBackupFailure) : DatabaseBackupInspectResult
}

sealed interface DatabaseRestoreResult {
    data class Success(
        val restoredCopyCount: Int,
        val automaticBackupName: String,
    ) : DatabaseRestoreResult

    data class Failure(val reason: DatabaseBackupFailure) : DatabaseRestoreResult
}

enum class DatabaseBackupFailure {
    READ_FAILED,
    WRITE_FAILED,
    TOO_LARGE,
    INVALID_ARCHIVE,
    CHECKSUM_MISMATCH,
    UNSUPPORTED_FORMAT,
    NEWER_DATABASE,
    INTEGRITY_FAILED,
    INSUFFICIENT_SPACE,
    RESTORE_FAILED,
}

interface DatabaseBackupManager {
    suspend fun createBackup(output: java.io.OutputStream): DatabaseBackupCreateResult

    suspend fun inspectBackup(input: java.io.InputStream): DatabaseBackupInspectResult

    suspend fun restoreBackup(preview: DatabaseBackupPreview): DatabaseRestoreResult
}
