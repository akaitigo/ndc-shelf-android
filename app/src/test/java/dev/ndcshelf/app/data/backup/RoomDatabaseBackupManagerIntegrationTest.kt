package dev.ndcshelf.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.ScanAttemptEntity
import dev.ndcshelf.app.data.local.ScanSessionEntity
import dev.ndcshelf.app.domain.backup.DatabaseBackupInspectResult
import dev.ndcshelf.app.domain.backup.DatabaseBackupFailure
import dev.ndcshelf.app.domain.backup.DatabaseBackupMetadata
import dev.ndcshelf.app.domain.backup.DatabaseBackupPreview
import dev.ndcshelf.app.domain.backup.DatabaseRestoreResult
import dev.ndcshelf.app.domain.backup.DatabaseSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomDatabaseBackupManagerIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var backupDirectory: File
    private lateinit var manager: RoomDatabaseBackupManager
    private var now = 0L
    private var spaceAvailable = true

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupDirectory = File(context.cacheDir, "backup-unit-test-${System.nanoTime()}")
        now = 1_800_000_000_000
        spaceAvailable = true
        manager = RoomDatabaseBackupManager(
            context = context,
            database = database,
            automaticBackupDirectory = backupDirectory,
            appVersion = "test",
            nowMillis = { now++ },
            spaceReservation = { spaceAvailable },
        )
    }

    @After
    fun tearDown() {
        database.close()
        backupDirectory.deleteRecursively()
    }

    @Test
    fun `restore preserves scan history in selected and rollback backups`() = runBlocking {
        val selected = snapshot("selected", "DUPLICATE")
        insert(selected)
        val output = ByteArrayOutputStream()
        manager.createBackup(output)

        clear()
        val replaced = snapshot("replaced", "NOT_FOUND")
        insert(replaced)
        val preview = manager.inspectBackup(ByteArrayInputStream(output.toByteArray()))
            as DatabaseBackupInspectResult.Valid

        val restoreResult = manager.restoreBackup(preview.preview)
        assertTrue(restoreResult.toString(), restoreResult is DatabaseRestoreResult.Success)
        val result = restoreResult as DatabaseRestoreResult.Success

        assertEquals(selected, readSnapshot())
        val rollbackFile = File(backupDirectory, result.automaticBackupName)
        assertTrue(rollbackFile.isFile)
        val rollback = manager.inspectBackup(rollbackFile.inputStream())
            as DatabaseBackupInspectResult.Valid
        assertEquals(replaced, rollback.preview.snapshot)
    }

    @Test
    fun `repeated failed restores keep only three verified rollback backups`() = runBlocking {
        val current = snapshot("current", "DUPLICATE")
        insert(current)
        val invalid = DatabaseSnapshot(
            works = emptyList(),
            editions = current.editions,
            copies = current.copies,
        )
        val preview = DatabaseBackupPreview(
            metadata = DatabaseBackupMetadata(
                formatVersion = 10,
                databaseVersion = 9,
                createdAt = 1,
                appVersion = "test",
                workCount = 0,
                editionCount = 1,
                copyCount = 1,
            ),
            snapshot = invalid,
        )

        repeat(6) {
            assertTrue(manager.restoreBackup(preview) is DatabaseRestoreResult.Failure)
            assertEquals(current, readSnapshot())
        }

        val backups = backupDirectory.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".ndcshelfbackup") }
        assertEquals(3, backups.size)
        backups.forEach { backup ->
            assertTrue(manager.inspectBackup(backup.inputStream()) is DatabaseBackupInspectResult.Valid)
        }
        assertTrue(backupDirectory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `failure before rollback creation prunes legacy overflow`() = runBlocking {
        val current = snapshot("current", "DUPLICATE")
        insert(current)
        val output = ByteArrayOutputStream()
        manager.createBackup(output)
        val preview = manager.inspectBackup(ByteArrayInputStream(output.toByteArray()))
            as DatabaseBackupInspectResult.Valid
        backupDirectory.mkdirs()
        repeat(5) { index ->
            File(backupDirectory, "before-restore-legacy-$index.ndcshelfbackup").apply {
                writeBytes(byteArrayOf(index.toByte()))
                setLastModified(index.toLong())
            }
        }
        spaceAvailable = false

        val result = manager.restoreBackup(preview.preview)

        assertEquals(
            DatabaseRestoreResult.Failure(DatabaseBackupFailure.INSUFFICIENT_SPACE),
            result,
        )
        assertEquals(current, readSnapshot())
        assertEquals(
            3,
            backupDirectory.listFiles().orEmpty().count { it.name.endsWith(".ndcshelfbackup") },
        )
    }

    private suspend fun insert(snapshot: DatabaseSnapshot) {
        val dao = database.libraryDao()
        dao.upsertWorks(snapshot.works)
        dao.upsertEditions(snapshot.editions)
        dao.upsertCopies(snapshot.copies)
        dao.upsertScanSessions(snapshot.scanSessions)
        dao.upsertScanAttempts(snapshot.scanAttempts)
    }

    private suspend fun clear() {
        val dao = database.libraryDao()
        dao.deleteAllScanAttempts()
        dao.deleteAllScanSessions()
        dao.deleteAllCopies()
        dao.deleteAllEditions()
        dao.deleteAllWorks()
    }

    private suspend fun readSnapshot(): DatabaseSnapshot {
        val dao = database.libraryDao()
        return DatabaseSnapshot(
            works = dao.getAllWorks(),
            editions = dao.getAllEditions(),
            copies = dao.getAllCopies(),
            scanSessions = dao.getAllScanSessions(),
            scanAttempts = dao.getAllScanAttempts(),
        )
    }

    private fun snapshot(prefix: String, outcome: String) = DatabaseSnapshot(
        works = listOf(BookWorkEntity("$prefix-work", "$prefix-title", "$prefix-author")),
        editions = listOf(
            BookEditionEntity(
                id = "$prefix-edition",
                workId = "$prefix-work",
                isbn13 = "9784101010014",
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
        scanSessions = listOf(ScanSessionEntity("$prefix-session", 10, 30)),
        scanAttempts = listOf(
            ScanAttemptEntity(
                id = "$prefix-attempt",
                sessionId = "$prefix-session",
                isbn = "9784101010014",
                outcome = outcome,
                copyId = null,
                copySnapshot = null,
                attemptedAt = 20,
                undoneAt = null,
            ),
        ),
    )
}
