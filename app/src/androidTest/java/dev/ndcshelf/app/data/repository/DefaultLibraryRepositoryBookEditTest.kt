package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.NdlBookMetadataService
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.AddBookResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultLibraryRepositoryBookEditTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: DefaultLibraryRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultLibraryRepository(database, NdlBookMetadataService())
        database.libraryDao().insertWork(
            BookWorkEntity("work-1", "旧題", "旧著者"),
        )
        database.libraryDao().insertEdition(
            BookEditionEntity(
                id = "edition-1",
                workId = "work-1",
                isbn13 = ISBN,
                publisher = "旧出版社",
                publishedYear = 2020,
                coverUrl = null,
                ndcCode = "014.45",
                ndcEdition = "NDC10",
                classificationSource = ClassificationSource.NDL.name,
            ),
        )
        database.libraryDao().insertCopy(
            OwnedCopyEntity(
                id = "copy-1",
                editionId = "edition-1",
                mediaType = "PHYSICAL",
                location = "旧棚",
                readingStatus = ReadingStatus.UNREAD.name,
                addedAt = 1_700_000_000_000L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun updateAndUndoPersistAllTablesAtomicallyAndPreserveManualSource() = runBlocking {
        val updated = repository.updateBook("copy-1", editedDraft()) as UpdateBookResult.Updated
        val stored = requireNotNull(database.libraryDao().findOwnedByCopyId("copy-1")).toDomain()

        assertEquals("新題", stored.title)
        assertEquals("新著者", stored.primaryAuthor)
        assertEquals("新出版社", stored.publisher)
        assertEquals(2024, stored.publishedYear)
        assertEquals("913.6", stored.ndcCode)
        assertEquals("NDC10", stored.ndcEdition)
        assertEquals(ClassificationSource.MANUAL, stored.classificationSource)
        assertEquals("新棚", stored.location)
        assertEquals(ReadingStatus.READ, stored.readingStatus)

        val duplicate = repository.addFromIsbn(ISBN)
        assertTrue(duplicate is AddBookResult.Duplicate)
        assertEquals(stored, database.libraryDao().findOwnedByCopyId("copy-1")?.toDomain())

        assertTrue(repository.restoreBook(updated.previous, updated.current))
        val restored = requireNotNull(database.libraryDao().findOwnedByCopyId("copy-1")).toDomain()
        assertEquals("旧題", restored.title)
        assertEquals("014.45", restored.ndcCode)
        assertEquals(ClassificationSource.NDL, restored.classificationSource)
    }

    @Test
    fun undoDoesNotOverwriteAConcurrentChange() = runBlocking {
        val updated = repository.updateBook("copy-1", editedDraft()) as UpdateBookResult.Updated
        database.libraryDao().updateCopy("copy-1", "別操作の棚", ReadingStatus.READ.name)

        assertFalse(repository.restoreBook(updated.previous, updated.current))
        assertEquals(
            "別操作の棚",
            database.libraryDao().findOwnedByCopyId("copy-1")?.location,
        )
    }

    @Test
    fun invalidEditDoesNotWriteAnyTable() = runBlocking {
        val before = requireNotNull(database.libraryDao().findOwnedByCopyId("copy-1")).toDomain()

        val result = repository.updateBook("copy-1", editedDraft().copy(title = " "))

        assertTrue(result is UpdateBookResult.Invalid)
        assertEquals(before, database.libraryDao().findOwnedByCopyId("copy-1")?.toDomain())
    }

    private fun editedDraft() = BookEditDraft(
        title = " 新題 ",
        primaryAuthor = " 新著者 ",
        publisher = " 新出版社 ",
        publishedYear = " 2024 ",
        ndcCode = " 913.6 ",
        ndcEdition = " NDC10 ",
        location = " 新棚 ",
        readingStatus = ReadingStatus.READ,
    )

    private companion object {
        const val ISBN = "9784820418078"
    }
}
