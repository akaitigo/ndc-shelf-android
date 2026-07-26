package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.BookMetadata
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.domain.importer.ImportApplyResult
import dev.ndcshelf.app.domain.importer.ImportConflictPolicy
import dev.ndcshelf.app.domain.importer.LibraryImportPreview
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.AddBookFailure
import dev.ndcshelf.app.domain.repository.DeleteBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultLibraryRepositoryIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addDuplicateUpdateAndDelete_useTheRealDaoAndTransactions() = runBlocking {
        var metadataCalls = 0
        val repository = repository(
            ids = listOf("work-new", "edition-new", "copy-new"),
            service = BookMetadataService {
                metadataCalls += 1
                BookMetadataLookupResult.Found(metadata())
            },
        )

        val added = repository.addFromIsbn(ISBN) as AddBookResult.Added

        assertEquals("work-new", added.book.workId)
        assertEquals("題名", added.book.title)
        assertEquals("著者A・著者B", added.book.primaryAuthor)
        assertEquals(ClassificationSource.NDL, added.book.classificationSource)
        assertEquals(1_700_000_000_000L, added.book.addedAt)
        assertEquals(1, database.libraryDao().getAllWorks().size)
        assertEquals(1, database.libraryDao().getAllEditions().size)
        assertEquals(1, database.libraryDao().getAllCopies().size)

        val duplicate = repository.addFromIsbn(ISBN) as AddBookResult.Duplicate
        assertEquals(added.book, duplicate.book)
        assertEquals(1, metadataCalls)

        val updated = repository.updateBook(
            added.book.copyId,
            BookEditDraft(
                title = "更新後",
                primaryAuthor = "新著者",
                publisher = "新出版社",
                publishedYear = "2025",
                ndcCode = "913.6",
                ndcEdition = "NDC10",
                location = "居間・棚B",
                readingStatus = ReadingStatus.READ,
            ),
        ) as UpdateBookResult.Updated
        assertEquals("更新後", updated.current.title)
        assertEquals(ClassificationSource.MANUAL, updated.current.classificationSource)
        assertEquals(
            "居間・棚B",
            database.libraryDao().findOwnedByCopyId(added.book.copyId)?.location,
        )

        val deleted = repository.deleteBook(added.book.copyId) as DeleteBookResult.Deleted
        assertEquals(updated.current, deleted.book)
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
    }

    @Test
    fun insertFailure_rollsBackTheWorkInsertedEarlierInTheTransaction() = runBlocking {
        database.libraryDao().insertWork(BookWorkEntity("existing-work", "既存", "既存著者"))
        database.libraryDao().insertEdition(
            BookEditionEntity(
                id = "edition-collision",
                workId = "existing-work",
                isbn13 = OTHER_ISBN,
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = ClassificationSource.UNKNOWN.name,
            ),
        )
        val repository = repository(
            ids = listOf("work-must-rollback", "edition-collision", "copy-new"),
            service = BookMetadataService { BookMetadataLookupResult.Found(metadata()) },
        )

        val result = repository.addFromIsbn(ISBN)

        assertTrue(result is AddBookResult.Failure)
        assertEquals(AddBookFailure.SAVE, (result as AddBookResult.Failure).reason)
        assertNull(database.libraryDao().findWorkById("work-must-rollback"))
        assertEquals(listOf("existing-work"), database.libraryDao().getAllWorks().map { it.id })
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
    }

    @Test
    fun cancellationFromMetadataLookup_isPropagatedWithoutWrites() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val repository = repository(
            ids = emptyList(),
            service = BookMetadataService { throw cancellation },
        )

        try {
            repository.addFromIsbn(ISBN)
            fail("CancellationException expected")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
    }

    @Test
    fun unknownPersistedEnums_fallBackToSafeDomainValues() = runBlocking {
        database.libraryDao().insertWork(BookWorkEntity("work-unknown", "題名", "著者"))
        database.libraryDao().insertEdition(
            BookEditionEntity(
                id = "edition-unknown",
                workId = "work-unknown",
                isbn13 = ISBN,
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = "FUTURE_SOURCE",
            ),
        )
        database.libraryDao().insertCopy(
            OwnedCopyEntity(
                id = "copy-unknown",
                editionId = "edition-unknown",
                mediaType = "FUTURE_MEDIA",
                location = "棚",
                readingStatus = "FUTURE_STATUS",
                addedAt = 1,
            ),
        )

        val book = requireNotNull(
            database.libraryDao().findOwnedByCopyId("copy-unknown"),
        ).toDomain()

        assertEquals(ClassificationSource.UNKNOWN, book.classificationSource)
        assertEquals(MediaType.PHYSICAL, book.mediaType)
        assertEquals(ReadingStatus.UNREAD, book.readingStatus)
    }

    @Test
    fun invalidImportConstraint_rollsBackAllThreeTables() = runBlocking {
        val repository = repository(
            emptyList(),
            BookMetadataService { BookMetadataLookupResult.NotFound },
        )
        val first = importBook("first", ISBN)
        val conflicting = importBook("second", ISBN)
        val preview = LibraryImportPreview(
            additions = listOf(first, conflicting),
            updates = emptyList(),
            skippedCount = 0,
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
            existingSnapshot = emptyList(),
        )

        val result = repository.applyImport(preview)

        assertTrue(result is ImportApplyResult.Failure)
        assertTrue(database.libraryDao().getAllWorks().isEmpty())
        assertTrue(database.libraryDao().getAllEditions().isEmpty())
        assertTrue(database.libraryDao().getAllCopies().isEmpty())
    }

    private fun repository(
        ids: List<String>,
        service: BookMetadataService,
    ): DefaultLibraryRepository {
        val remainingIds = ArrayDeque(ids)
        return DefaultLibraryRepository(
            database = database,
            metadataService = service,
            idFactory = { remainingIds.removeFirst() },
            nowMillis = { 1_700_000_000_000L },
        )
    }

    private fun metadata() = BookMetadata(
        title = "題名",
        authors = listOf("著者A", "著者B"),
        publisher = "出版社",
        publishedYear = 2024,
        editionStatement = null,
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        coverUrl = "https://ndlsearch.ndl.go.jp/thumbnail/$ISBN.jpg",
    )

    private fun importBook(id: String, isbn: String) = LibraryBook(
        copyId = "copy-$id",
        workId = "work-$id",
        editionId = "edition-$id",
        title = "題名$id",
        primaryAuthor = "著者",
        isbn13 = isbn,
        publisher = null,
        publishedYear = 2024,
        coverUrl = null,
        ndcCode = null,
        ndcEdition = null,
        classificationSource = ClassificationSource.UNKNOWN,
        mediaType = MediaType.PHYSICAL,
        location = "棚",
        readingStatus = ReadingStatus.UNREAD,
        addedAt = 1,
    )

    private companion object {
        const val ISBN = "9784820418078"
        const val OTHER_ISBN = "9784101010014"
    }
}
