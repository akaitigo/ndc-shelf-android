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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupDirectory = File(context.cacheDir, "backup-unit-test-${System.nanoTime()}")
        manager = RoomDatabaseBackupManager(
            context = context,
            database = database,
            automaticBackupDirectory = backupDirectory,
            appVersion = "test",
            nowMillis = { 1_800_000_000_000 },
            spaceReservation = { true },
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
