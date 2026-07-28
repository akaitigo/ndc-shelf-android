package dev.ndcshelf.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.ScanAttemptEntity
import dev.ndcshelf.app.data.local.ScanSessionEntity
import dev.ndcshelf.app.data.local.WishlistItemEntity
import dev.ndcshelf.app.domain.backup.DatabaseBackupInspectResult
import dev.ndcshelf.app.domain.backup.DatabaseBackupMetadata
import dev.ndcshelf.app.domain.backup.DatabaseBackupPreview
import dev.ndcshelf.app.domain.backup.DatabaseRestoreResult
import dev.ndcshelf.app.domain.backup.DatabaseSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomDatabaseBackupManagerTest {
    private lateinit var database: AppDatabase
    private lateinit var backupDirectory: File
    private lateinit var manager: RoomDatabaseBackupManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        backupDirectory = File(context.cacheDir, "backup-test-${System.nanoTime()}")
        manager = RoomDatabaseBackupManager(
            context = context,
            database = database,
            automaticBackupDirectory = backupDirectory,
            appVersion = "test",
            nowMillis = { 1_700_000_000_000 },
        )
    }

    @After
    fun tearDown() {
        database.close()
        backupDirectory.deleteRecursively()
    }

    @Test
    fun backupAndRestore_replacesAllTablesAndKeepsAutomaticBackup() = runTest {
        insert(sampleSnapshot("original"))
        val output = ByteArrayOutputStream()
        manager.createBackup(output)
        clear()
        insert(sampleSnapshot("replacement"))
        val inspected = manager.inspectBackup(ByteArrayInputStream(output.toByteArray()))
            as DatabaseBackupInspectResult.Valid

        val result = manager.restoreBackup(inspected.preview) as DatabaseRestoreResult.Success

        assertEquals(sampleSnapshot("original"), readSnapshot())
        assertEquals("original-session", database.libraryDao().getAllScanSessions().single().id)
        assertEquals("original-attempt", database.libraryDao().getAllScanAttempts().single().id)
        assertEquals(1, result.restoredCopyCount)
        assertTrue(File(backupDirectory, result.automaticBackupName).isFile)
    }

    @Test
    fun failedRestore_rollsBackCurrentDatabase() = runTest {
        val current = sampleSnapshot("current")
        insert(current)
        backupDirectory.mkdirs()
        repeat(5) { index ->
            File(backupDirectory, "before-restore-old-$index.ndcshelfbackup").apply {
                writeText("old")
                setLastModified(index.toLong())
            }
        }
        val invalid = DatabaseSnapshot(
            works = emptyList(),
            editions = sampleSnapshot("invalid").editions,
            copies = sampleSnapshot("invalid").copies,
        )
        val preview = DatabaseBackupPreview(
            metadata = DatabaseBackupMetadata(2, 1, 1, "test", 0, 1, 1),
            snapshot = invalid,
        )

        val result = manager.restoreBackup(preview)

        assertTrue(result is DatabaseRestoreResult.Failure)
        assertEquals(current, readSnapshot())
        assertEquals(
            3,
            backupDirectory.listFiles()?.count { it.name.endsWith(".ndcshelfbackup") },
        )
    }

    private suspend fun insert(snapshot: DatabaseSnapshot) {
        database.libraryDao().upsertWorks(snapshot.works)
        database.libraryDao().upsertEditions(snapshot.editions)
        database.libraryDao().upsertWishlistItems(snapshot.wishlistItems)
        database.libraryDao().upsertCopies(snapshot.copies)
        database.libraryDao().upsertScanSessions(snapshot.scanSessions)
        database.libraryDao().upsertScanAttempts(snapshot.scanAttempts)
    }

    private suspend fun clear() {
        database.libraryDao().deleteAllScanAttempts()
        database.libraryDao().deleteAllScanSessions()
        database.libraryDao().deleteAllCopies()
        database.libraryDao().deleteAllWishlistItems()
        database.libraryDao().deleteAllEditions()
        database.libraryDao().deleteAllWorks()
    }

    private suspend fun readSnapshot() = DatabaseSnapshot(
        works = database.libraryDao().getAllWorks(),
        editions = database.libraryDao().getAllEditions(),
        copies = database.libraryDao().getAllCopies(),
        wishlistItems = database.libraryDao().getAllWishlistItems(),
        scanSessions = database.libraryDao().getAllScanSessions(),
        scanAttempts = database.libraryDao().getAllScanAttempts(),
    )

    private fun sampleSnapshot(prefix: String) = DatabaseSnapshot(
        works = listOf(BookWorkEntity("$prefix-work", "$prefix-title", "$prefix-author")),
        editions = listOf(
            BookEditionEntity(
                id = "$prefix-edition",
                workId = "$prefix-work",
                isbn13 = if (prefix == "original") "9784101010014" else "9784003101018",
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = "UNKNOWN",
            ),
        ),
        copies = listOf(
            OwnedCopyEntity(
                id = "$prefix-copy",
                editionId = "$prefix-edition",
                mediaType = "PHYSICAL",
                location = "未設定",
                readingStatus = "UNREAD",
                addedAt = 1,
            ),
        ),
        wishlistItems = listOf(
            WishlistItemEntity(
                editionId = "$prefix-edition",
                status = "WANTED",
                createdAt = 1,
                updatedAt = 1,
            ),
        ),
        scanSessions = listOf(ScanSessionEntity("$prefix-session", 1, 3)),
        scanAttempts = listOf(
            ScanAttemptEntity(
                id = "$prefix-attempt",
                sessionId = "$prefix-session",
                isbn = if (prefix == "original") "9784101010014" else "9784003101018",
                outcome = "ADDED",
                copyId = "$prefix-copy",
                copySnapshot = "a".repeat(64),
                attemptedAt = 2,
                undoneAt = null,
            ),
        ),
    )
}
