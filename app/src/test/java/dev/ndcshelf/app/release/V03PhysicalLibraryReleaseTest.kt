package dev.ndcshelf.app.release

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import dev.ndcshelf.app.data.remote.BookMetadata
import dev.ndcshelf.app.data.remote.BookMetadataFailure
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.data.repository.DefaultLibraryRepository
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.PurchaseStatus
import dev.ndcshelf.app.domain.model.PurchaseTransition
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.model.ScanAttemptOutcome
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.BookstoreChangeResult
import dev.ndcshelf.app.domain.repository.BookstoreLookupResult
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.RestoreDeletedBookResult
import dev.ndcshelf.app.domain.repository.ScanUndoResult
import dev.ndcshelf.app.domain.repository.ShelfMoveDirection
import dev.ndcshelf.app.domain.repository.ShelfMoveResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V03PhysicalLibraryReleaseTest {
    private lateinit var database: AppDatabase
    private val idCounter = AtomicInteger()
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun multipleCopiesCanBePlacedReorderedEditedUndoneDeletedAndRestored() = runBlocking {
        val repository = repository { isbn -> BookMetadataLookupResult.Found(metadata(isbn)) }
        val first = (repository.addFromIsbn(ISBN_A) as AddBookResult.Added).book
        val second = (repository.addAnotherCopy(ISBN_A, "保存用") as AddBookResult.Added).book
        insertAnonymousTier()

        val placedFirst = update(
            repository,
            first,
            first.editDraft(
                readingStatus = ReadingStatus.READING,
                tierId = TIER_ID,
                insertAtStart = true,
            ),
        ).current
        val placedSecond = update(
            repository,
            second,
            second.editDraft(
                readingStatus = ReadingStatus.UNREAD,
                tierId = TIER_ID,
                insertAfterCopyId = first.copyId,
            ),
        ).current

        assertSame(
            ShelfMoveResult.Moved,
            repository.moveBookWithinTier(second.copyId, ShelfMoveDirection.LEFT),
        )
        assertEquals(
            listOf(second.copyId, first.copyId),
            database.locationDao().getOrderedCopies(TIER_ID).map { it.id },
        )

        val edited = update(
            repository,
            placedFirst,
            placedFirst.editDraft(readingStatus = ReadingStatus.READ, tierId = TIER_ID),
        )
        assertTrue(repository.restoreBook(edited.previous, edited.current))
        assertEquals(
            ReadingStatus.READING,
            repository.getLibrarySnapshot().single { it.copyId == first.copyId }.readingStatus,
        )

        val deleted = repository.deleteBook(second.copyId) as DeleteBookResult.Deleted
        assertEquals(1, repository.getLibrarySnapshot().size)
        assertSame(RestoreDeletedBookResult.Restored, repository.restoreDeletedBook(deleted.book))

        val restored = repository.getLibrarySnapshot().sortedBy { it.copyLabel }
        assertEquals(2, restored.size)
        assertEquals(setOf("1冊目", "保存用"), restored.map { it.copyLabel }.toSet())
        assertTrue(restored.all { it.location == "匿名室 / 匿名棚 / 匿名段" })
        assertEquals(placedSecond.addedAt, restored.single { it.copyId == second.copyId }.addedAt)
    }

    @Test
    fun bookstoreUsesOnlineMetadataAndKeepsSavedStateAvailableOffline() = runBlocking {
        var onlineCalls = 0
        val online = repository { isbn ->
            onlineCalls += 1
            BookMetadataLookupResult.Found(metadata(isbn))
        }
        val candidate = (online.lookupBookstore(ISBN_B) as BookstoreLookupResult.Found).book
        val wanted = (online.changePurchaseState(candidate, PurchaseTransition.WANTED)
            as BookstoreChangeResult.Updated).book
        assertEquals(PurchaseStatus.WANTED, wanted.purchaseStatus)
        assertEquals(1, onlineCalls)

        var offlineCalls = 0
        val offline = repository {
            offlineCalls += 1
            BookMetadataLookupResult.Failure(BookMetadataFailure.OFFLINE)
        }
        val local = (offline.lookupBookstore(ISBN_B) as BookstoreLookupResult.Found).book
        assertEquals(PurchaseStatus.WANTED, local.purchaseStatus)
        assertEquals(0, offlineCalls)

        val unavailable = offline.lookupBookstore(ISBN_C) as BookstoreLookupResult.Failure
        assertEquals(AddBookFailure.OFFLINE, unavailable.reason)
        assertEquals(1, offlineCalls)

        val purchased = offline.changePurchaseState(local, PurchaseTransition.PURCHASED)
            as BookstoreChangeResult.Updated
        assertEquals(1, purchased.book.ownedCopyCount)
        assertEquals(null, purchased.book.purchaseStatus)
    }

    @Test
    fun hundredAttemptScanSessionKeepsOutcomesAndBulkUndoOnlyRemovesAddedCopy() = runBlocking {
        val repository = repository { isbn ->
            if (isbn == ISBN_C) {
                BookMetadataLookupResult.Found(metadata(isbn))
            } else {
                BookMetadataLookupResult.Failure(BookMetadataFailure.OFFLINE)
            }
        }
        val sessionId = requireNotNull(repository.startScanSession())
        val added = repository.addFromIsbn(ISBN_C) as AddBookResult.Added
        assertTrue(repository.recordScanAttempt(sessionId, ISBN_C, added))

        repeat(49) {
            val duplicate = repository.addFromIsbn(ISBN_C) as AddBookResult.Duplicate
            assertTrue(repository.recordScanAttempt(sessionId, ISBN_C, duplicate))
        }
        repeat(50) {
            val failure = repository.addFromIsbn(ISBN_D) as AddBookResult.Failure
            assertEquals(AddBookFailure.OFFLINE, failure.reason)
            assertTrue(repository.recordScanAttempt(sessionId, ISBN_D, failure))
        }
        assertTrue(repository.finishScanSession(sessionId))

        val session = repository.observeScanSessions().first().single()
        assertEquals(100, session.attempts.size)
        assertEquals(1, session.attempts.count { it.outcome == ScanAttemptOutcome.ADDED })
        assertEquals(49, session.attempts.count { it.outcome == ScanAttemptOutcome.DUPLICATE })
        assertEquals(50, session.attempts.count { it.outcome == ScanAttemptOutcome.FAILURE })

        assertEquals(ScanUndoResult.Undone(1), repository.undoScanSession(sessionId))
        assertTrue(repository.getLibrarySnapshot().isEmpty())
        assertEquals(0, repository.observeScanSessions().first().single().activeAddedCount)
    }

    private fun repository(service: BookMetadataService) = DefaultLibraryRepository(
        database = database,
        metadataService = service,
        idFactory = { "v03-${idCounter.incrementAndGet()}" },
        nowMillis = { now++ },
    )

    private suspend fun insertAnonymousTier() {
        database.locationDao().insertRoom(LocationRoomEntity("room", "匿名室", 0))
        database.locationDao().insertShelf(LocationShelfEntity("shelf", "room", "匿名棚", 0))
        database.locationDao().insertTier(LocationTierEntity(TIER_ID, "shelf", "匿名段", 0))
    }

    private suspend fun update(
        repository: DefaultLibraryRepository,
        book: LibraryBook,
        draft: BookEditDraft,
    ): UpdateBookResult.Updated = repository.updateBook(book.copyId, draft) as UpdateBookResult.Updated

    private fun LibraryBook.editDraft(
        readingStatus: ReadingStatus,
        tierId: String,
        insertAtStart: Boolean = false,
        insertAfterCopyId: String? = null,
    ) = BookEditDraft(
        title = title,
        primaryAuthor = primaryAuthor,
        publisher = publisher.orEmpty(),
        publishedYear = publishedYear?.toString().orEmpty(),
        ndcCode = ndcCode.orEmpty(),
        ndcEdition = ndcEdition.orEmpty(),
        location = location,
        readingStatus = readingStatus,
        locationTierId = tierId,
        locationInsertAfterCopyId = insertAfterCopyId,
        locationInsertAtStart = insertAtStart,
        locationPositionSpecified = true,
        copyLabel = copyLabel,
    )

    private fun metadata(isbn: String) = BookMetadata(
        title = "匿名図書${isbn.takeLast(4)}",
        authors = listOf("匿名著者"),
        publisher = "匿名出版社",
        publishedYear = 2026,
        editionStatement = null,
        ndcCode = "913.6",
        ndcEdition = "NDC10",
        coverUrl = null,
    )

    private companion object {
        const val ISBN_A = "9784820418078"
        const val ISBN_B = "9784101010014"
        const val ISBN_C = "9780306406157"
        const val ISBN_D = "9791090636071"
        const val TIER_ID = "tier"
    }
}
