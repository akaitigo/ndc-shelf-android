package dev.ndcshelf.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.APP_DATABASE_VERSION
import dev.ndcshelf.app.domain.backup.BackupCodecException
import dev.ndcshelf.app.domain.backup.DatabaseBackupCodec
import dev.ndcshelf.app.domain.backup.DatabaseBackupCreateResult
import dev.ndcshelf.app.domain.backup.DatabaseBackupFailure
import dev.ndcshelf.app.domain.backup.DatabaseBackupInspectResult
import dev.ndcshelf.app.domain.backup.DatabaseBackupManager
import dev.ndcshelf.app.domain.backup.DatabaseBackupPreview
import dev.ndcshelf.app.domain.backup.DatabaseRestoreResult
import dev.ndcshelf.app.domain.backup.DatabaseSnapshot
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoomDatabaseBackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val automaticBackupDirectory: File,
    private val appVersion: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DatabaseBackupManager {
    private val dao = database.libraryDao()
    private val locationDao = database.locationDao()
    private val seriesDao = database.seriesDao()
    private val codec = DatabaseBackupCodec(APP_DATABASE_VERSION)

    override suspend fun createBackup(output: OutputStream): DatabaseBackupCreateResult = try {
        val snapshot = database.withTransaction { readSnapshot() }
        val (archive, metadata) = codec.encode(snapshot, appVersion, nowMillis())
        output.use {
            it.write(archive)
            it.flush()
        }
        DatabaseBackupCreateResult.Success(metadata)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: BackupCodecException) {
        DatabaseBackupCreateResult.Failure(error.failure)
    } catch (_: Exception) {
        DatabaseBackupCreateResult.Failure(DatabaseBackupFailure.WRITE_FAILED)
    }

    override suspend fun inspectBackup(input: InputStream): DatabaseBackupInspectResult = try {
        val preview = input.use(codec::decode)
        DatabaseBackupInspectResult.Valid(preview)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: BackupCodecException) {
        DatabaseBackupInspectResult.Invalid(error.failure)
    } catch (_: Exception) {
        DatabaseBackupInspectResult.Invalid(DatabaseBackupFailure.INVALID_ARCHIVE)
    }

    override suspend fun restoreBackup(
        preview: DatabaseBackupPreview,
    ): DatabaseRestoreResult = try {
        automaticBackupDirectory.mkdirs()
        if (!automaticBackupDirectory.isDirectory) {
            return DatabaseRestoreResult.Failure(DatabaseBackupFailure.WRITE_FAILED)
        }

        var automaticBackupName = ""
        database.withTransaction {
            val current = readSnapshot()
            val (archive, _) = codec.encode(current, appVersion, nowMillis())
            if (!reserveAutomaticBackupSpace(archive.size * REQUIRED_SPACE_MULTIPLIER)) {
                throw BackupCodecException(
                    DatabaseBackupFailure.INSUFFICIENT_SPACE,
                    "Insufficient space for rollback backup",
                )
            }
            val automaticBackup = writeAutomaticBackup(archive)
            val verifiedBackup = automaticBackup.inputStream().use(codec::decode)
            check(verifiedBackup.snapshot == current) { "Rollback backup verification failed" }
            automaticBackupName = automaticBackup.name

            dao.deleteAllScanAttempts()
            dao.deleteAllScanSessions()
            dao.deleteAllCopies()
            dao.deleteAllWishlistItems()
            seriesDao.deleteAllMemberships()
            seriesDao.deleteAllSeries()
            locationDao.deleteAllTiers()
            locationDao.deleteAllShelves()
            locationDao.deleteAllRooms()
            dao.deleteAllEditions()
            dao.deleteAllWorks()
            dao.upsertWorks(preview.snapshot.works)
            seriesDao.upsertSeriesItems(preview.snapshot.series)
            seriesDao.upsertMemberships(preview.snapshot.seriesMemberships)
            dao.upsertEditions(preview.snapshot.editions)
            dao.upsertWishlistItems(preview.snapshot.wishlistItems)
            locationDao.upsertRooms(preview.snapshot.rooms)
            locationDao.upsertShelves(preview.snapshot.shelves)
            locationDao.upsertTiers(preview.snapshot.tiers)
            dao.upsertCopies(preview.snapshot.copies)

            check(readSnapshot() == preview.snapshot) { "Restored snapshot differs" }
        }
        pruneAutomaticBackups(keepName = automaticBackupName)
        DatabaseRestoreResult.Success(
            restoredCopyCount = preview.metadata.copyCount,
            automaticBackupName = automaticBackupName,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: BackupCodecException) {
        DatabaseRestoreResult.Failure(error.failure)
    } catch (_: Exception) {
        DatabaseRestoreResult.Failure(DatabaseBackupFailure.RESTORE_FAILED)
    }

    private suspend fun readSnapshot() = DatabaseSnapshot(
        works = dao.getAllWorks(),
        editions = dao.getAllEditions(),
        copies = dao.getAllCopies(),
        rooms = locationDao.getRooms(),
        shelves = locationDao.getAllShelves(),
        tiers = locationDao.getAllTiers(),
        wishlistItems = dao.getAllWishlistItems(),
        series = seriesDao.getAllSeries(),
        seriesMemberships = seriesDao.getAllMemberships(),
    )

    private fun writeAutomaticBackup(archive: ByteArray): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(nowMillis()))
        val finalFile = File(automaticBackupDirectory, "before-restore-$timestamp.ndcshelfbackup")
        val temporaryFile = File(automaticBackupDirectory, ".${finalFile.name}.tmp")
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(archive)
                output.flush()
                output.fd.sync()
            }
            check(temporaryFile.renameTo(finalFile)) { "Could not commit rollback backup" }
        } finally {
            temporaryFile.delete()
        }
        return finalFile
    }

    private fun reserveAutomaticBackupSpace(requiredBytes: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val storageManager = context.getSystemService(StorageManager::class.java)
            val storageUuid = storageManager.getUuidForPath(automaticBackupDirectory)
            if (storageManager.getAllocatableBytes(storageUuid) < requiredBytes) return false
            return try {
                storageManager.allocateBytes(storageUuid, requiredBytes)
                true
            } catch (_: Exception) {
                false
            }
        }
        return hasLegacyFreeSpace(requiredBytes)
    }

    @SuppressLint("UsableSpace")
    private fun hasLegacyFreeSpace(requiredBytes: Long): Boolean =
        automaticBackupDirectory.usableSpace >= requiredBytes

    private fun pruneAutomaticBackups(keepName: String) {
        automaticBackupDirectory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".ndcshelfbackup") && it.name != keepName }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_OLDER_AUTOMATIC_BACKUPS)
            ?.forEach(File::delete)
    }

    companion object {
        private const val REQUIRED_SPACE_MULTIPLIER = 2L
        private const val MAX_OLDER_AUTOMATIC_BACKUPS = 2
    }
}
