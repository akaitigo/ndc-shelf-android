package dev.ndcshelf.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.data.repository.DefaultLibraryRepository
import dev.ndcshelf.app.domain.model.ManualBookDraft
import dev.ndcshelf.app.domain.repository.ManualBookResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.sync.SyncMutationJournal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncRepositoryJournalIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun enabledSyncCommitsDomainAndJournalTogether() = runBlocking {
        val engine = RoomSyncEngine(database, RoomSyncDomainStore(database))
        engine.initializeDevice("device-a")
        val repository = repository(engine)

        val result = repository.addManualBook(ManualBookDraft(title = "同期する本"))

        assertTrue(result is ManualBookResult.Added)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(3, engine.pendingOperations().size)
        val status = RoomSyncStatusRepository(database).observeStatus()
            .first { it.pendingOperationCount == 3 }
        assertTrue(status.enabled)
        assertEquals(0, status.unresolvedConflictCount)
    }

    @Test
    fun journalFailureRollsBackDomainMutation() = runBlocking {
        val repository = repository(
            object : SyncMutationJournal {
                override suspend fun record(mutations: List<dev.ndcshelf.app.domain.sync.SyncMutation>) {
                    error("journal failure")
                }
            },
        )

        val result = repository.addManualBook(ManualBookDraft(title = "保存されない本"))

        assertSame(ManualBookResult.Failure, result)
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
    }

    @Test
    fun undoAfterSynchronizedDeleteUsesNewIdsInsteadOfResurrectingTombstones() = runBlocking {
        val engine = RoomSyncEngine(database, RoomSyncDomainStore(database))
        engine.initializeDevice("device-a")
        val repository = repository(engine, sixIds = true)
        val added = repository.addManualBook(ManualBookDraft(title = "復元する本")) as ManualBookResult.Added
        val deleted = repository.deleteBook(added.book.copyId) as DeleteBookResult.Deleted

        assertSame(RestoreDeletedBookResult.Restored, repository.restoreDeletedBook(deleted.book))

        val restored = database.libraryDao().getLibrary().single()
        assertEquals("work-restored", restored.workId)
        assertEquals("edition-restored", restored.editionId)
        assertEquals("copy-restored", restored.copyId)
    }

    private fun repository(
        journal: SyncMutationJournal,
        sixIds: Boolean = false,
    ): DefaultLibraryRepository {
        val ids = ArrayDeque(
            listOf("work", "edition", "copy") + if (sixIds) {
                listOf("work-restored", "edition-restored", "copy-restored")
            } else emptyList(),
        )
        return DefaultLibraryRepository(
            database = database,
            metadataService = BookMetadataService { error("network must not be called") },
            idFactory = { ids.removeFirst() },
            nowMillis = { 1_000 },
            syncJournal = journal,
        )
    }
}
