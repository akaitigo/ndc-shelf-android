package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.data.sync.RoomSyncDomainStore
import dev.ndcshelf.app.data.sync.RoomSyncEngine
import dev.ndcshelf.app.domain.model.PartialDate
import dev.ndcshelf.app.domain.model.ReadingSessionDraft
import dev.ndcshelf.app.domain.model.ReadingSessionStatus
import dev.ndcshelf.app.domain.repository.AddReadingSessionResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.DeleteReadingSessionResult
import dev.ndcshelf.app.domain.repository.RestoreReadingSessionResult
import dev.ndcshelf.app.domain.repository.UpdateReadingSessionResult
import dev.ndcshelf.app.domain.sync.SyncMutation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomReadingHistoryRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private var now = 1_800_000_000_000L
    private var nextId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun sessionLifecycleDerivesCopyReadingStatus() =
        runBlocking {
            insertBook()
            val repository = repository()

            val added =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(status = ReadingSessionStatus.READING, startedDay = "2026-07"),
                ) as AddReadingSessionResult.Added

            assertEquals("READING", copyStatus())
            assertEquals(PartialDate(2026, 7), added.session.startedDay)

            val paused =
                repository.updateSession(
                    added.session.id,
                    ReadingSessionDraft(status = ReadingSessionStatus.PAUSED, startedDay = "2026-07"),
                )
            assertTrue(paused.toString(), paused is UpdateReadingSessionResult.Updated)
            assertEquals("PAUSED", copyStatus())

            val finished =
                repository.updateSession(
                    added.session.id,
                    ReadingSessionDraft(
                        status = ReadingSessionStatus.FINISHED,
                        startedDay = "2026-07",
                        finishedDay = "2026-07-29",
                        rating = 4,
                        note = "再読したい",
                    ),
                ) as UpdateReadingSessionResult.Updated
            assertEquals("READ", copyStatus())
            assertEquals(PartialDate(2026, 7, 29), finished.current.finishedDay)
            assertEquals(4, finished.current.rating)

            val sessions = repository.observeSessionsForEdition(EDITION_ID).first()
            assertEquals(1, sessions.size)
            assertEquals("再読したい", sessions.single().note)
        }

    @Test
    fun multipleRereadsCoexistAndKeepDerivedStatusConsistent() =
        runBlocking {
            insertBook()
            val repository = repository()

            val first =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(
                        status = ReadingSessionStatus.FINISHED,
                        startedDay = "2024",
                        finishedDay = "2024",
                    ),
                )
            assertTrue(first is AddReadingSessionResult.Added)
            assertEquals("READ", copyStatus())

            val second =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(
                        status = ReadingSessionStatus.FINISHED,
                        startedDay = "2025-01",
                        finishedDay = "2025-02",
                        rating = 5,
                    ),
                )
            assertTrue(second is AddReadingSessionResult.Added)

            val reread =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(status = ReadingSessionStatus.READING, startedDay = "2026-07-29"),
                )
            assertTrue(reread is AddReadingSessionResult.Added)
            assertEquals("READING", copyStatus())

            assertEquals(3, repository.observeSessionsForEdition(EDITION_ID).first().size)
        }

    @Test
    fun duplicateAndConcurrentActiveSessionsAreRejected() =
        runBlocking {
            insertBook()
            val repository = repository()

            val draft =
                ReadingSessionDraft(
                    status = ReadingSessionStatus.FINISHED,
                    startedDay = "2026",
                    finishedDay = "2026",
                    note = "同一内容",
                )
            assertTrue(repository.addSession(COPY_ID, draft) is AddReadingSessionResult.Added)
            assertSame(AddReadingSessionResult.Duplicate, repository.addSession(COPY_ID, draft))

            val active =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(status = ReadingSessionStatus.READING),
                )
            assertTrue(active is AddReadingSessionResult.Added)
            assertSame(
                AddReadingSessionResult.ActiveSessionExists,
                repository.addSession(COPY_ID, ReadingSessionDraft(status = ReadingSessionStatus.PAUSED)),
            )
            assertSame(
                AddReadingSessionResult.CopyNotFound,
                repository.addSession("missing-copy", ReadingSessionDraft(ReadingSessionStatus.READING)),
            )
        }

    @Test
    fun deleteRecomputesStatusAndUndoRestoresTheSession() =
        runBlocking {
            insertBook()
            val repository = repository()

            val finished =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(
                        status = ReadingSessionStatus.FINISHED,
                        startedDay = "2024",
                        finishedDay = "2024",
                    ),
                ) as AddReadingSessionResult.Added
            val reading =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(status = ReadingSessionStatus.READING, startedDay = "2026"),
                ) as AddReadingSessionResult.Added
            assertEquals("READING", copyStatus())

            val deleted = repository.deleteSession(reading.session.id) as DeleteReadingSessionResult.Deleted
            assertEquals("READ", copyStatus())

            assertSame(
                RestoreReadingSessionResult.Restored,
                repository.restoreSession(deleted.session),
            )
            assertEquals("READING", copyStatus())
            assertEquals(2, repository.observeSessionsForEdition(EDITION_ID).first().size)

            // 既存IDが残ったままの復元は上書きせず競合として拒否する。
            assertSame(
                RestoreReadingSessionResult.Conflict,
                repository.restoreSession(deleted.session),
            )

            // 最後の履歴を消しても手動設定を尊重してstatusは変更しない。
            val lastDelete = repository.deleteSession(finished.session.id)
            assertTrue(lastDelete is DeleteReadingSessionResult.Deleted)
            repository.deleteSession(
                repository
                    .observeSessionsForEdition(EDITION_ID)
                    .first()
                    .single()
                    .id,
            )
            assertEquals(0, repository.observeSessionsForEdition(EDITION_ID).first().size)
            assertEquals("READING", copyStatus())
            assertSame(
                DeleteReadingSessionResult.NotFound,
                repository.deleteSession(finished.session.id),
            )
        }

    @Test
    fun mutationsAreJournaledAndTombstonedUndoUsesANewId() =
        runBlocking {
            insertBook()
            val engine = RoomSyncEngine(database, RoomSyncDomainStore(database))
            engine.initializeDevice("device-a")
            val repository = repository(engine)

            val added =
                repository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(status = ReadingSessionStatus.READING, startedDay = "2026-07"),
                ) as AddReadingSessionResult.Added
            val afterAdd = engine.pendingOperations().map { it.mutation }
            assertTrue(
                afterAdd.any {
                    it is SyncMutation.Upsert && it.entityType == "readingSession" &&
                        it.entityId == added.session.id
                },
            )
            assertTrue(
                afterAdd.any { it is SyncMutation.Upsert && it.entityType == "ownedCopy" },
            )

            val deleted = repository.deleteSession(added.session.id) as DeleteReadingSessionResult.Deleted
            assertTrue(
                engine.pendingOperations().map { it.mutation }.any {
                    it is SyncMutation.Delete && it.entityType == "readingSession" &&
                        it.entityId == added.session.id
                },
            )

            assertSame(
                RestoreReadingSessionResult.Restored,
                repository.restoreSession(deleted.session),
            )
            val restored = repository.observeSessionsForEdition(EDITION_ID).first().single()
            assertNotEquals(added.session.id, restored.id)
            assertEquals(added.session.startedDay, restored.startedDay)
        }

    @Test
    fun deletingTheCopyJournalsReadingSessionDeletes() =
        runBlocking {
            insertBook()
            val engine = RoomSyncEngine(database, RoomSyncDomainStore(database))
            engine.initializeDevice("device-a")
            val historyRepository = repository(engine)
            val libraryRepository =
                DefaultLibraryRepository(
                    database = database,
                    metadataService = BookMetadataService { BookMetadataLookupResult.NotFound },
                    idFactory = { "library-${nextId++}" },
                    nowMillis = { now++ },
                    syncJournal = engine,
                )

            val added =
                historyRepository.addSession(
                    COPY_ID,
                    ReadingSessionDraft(status = ReadingSessionStatus.READING),
                ) as AddReadingSessionResult.Added

            val deleteResult = libraryRepository.deleteBook(COPY_ID)
            assertTrue(deleteResult.toString(), deleteResult is DeleteBookResult.Deleted)
            assertEquals(0, database.readingSessionDao().getAll().size)
            assertTrue(
                engine.pendingOperations().map { it.mutation }.any {
                    it is SyncMutation.Delete && it.entityType == "readingSession" &&
                        it.entityId == added.session.id
                },
            )
        }

    private fun repository(
        syncJournal: dev.ndcshelf.app.domain.sync.SyncMutationJournal =
            dev.ndcshelf.app.domain.sync.SyncMutationJournal.Disabled,
    ) = RoomReadingHistoryRepository(
        database = database,
        idFactory = { "session-${nextId++}" },
        nowMillis = { now++ },
        syncJournal = syncJournal,
    )

    private suspend fun copyStatus(): String = requireNotNull(database.libraryDao().findCopyById(COPY_ID)).readingStatus

    private suspend fun insertBook() {
        database.libraryDao().insertWork(BookWorkEntity(WORK_ID, "題名", "著者"))
        database.libraryDao().insertEdition(
            BookEditionEntity(
                id = EDITION_ID,
                workId = WORK_ID,
                isbn13 = "9784101010014",
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = "UNKNOWN",
            ),
        )
        database.libraryDao().insertCopy(
            OwnedCopyEntity(
                id = COPY_ID,
                editionId = EDITION_ID,
                mediaType = "PHYSICAL",
                location = "未設定",
                readingStatus = "UNREAD",
                addedAt = 1,
            ),
        )
    }

    private companion object {
        const val WORK_ID = "work-1"
        const val EDITION_ID = "edition-1"
        const val COPY_ID = "copy-1"
    }
}
