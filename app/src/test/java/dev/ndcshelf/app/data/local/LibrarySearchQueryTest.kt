package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort
import dev.ndcshelf.app.domain.model.ReadingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrarySearchQueryTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.libraryDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun searchesEverySupportedFieldAndTreatsWildcardsLiterally() = runBlocking {
        insertBooks(
            listOf(
                sample(0, title = "郷土%資料", status = ReadingStatus.READING),
                sample(1, author = "地域著者"),
                sample(2, isbn = "9784820418078"),
                sample(3, ndc = "014.45"),
                sample(4, location = "書庫特別棚"),
            ),
        )

        assertEquals(listOf("copy-0"), search("郷土%").map(LibraryBookRow::copyId))
        assertEquals(listOf("copy-1"), search("地域著者").map(LibraryBookRow::copyId))
        assertEquals(listOf("copy-2"), search("18078").map(LibraryBookRow::copyId))
        assertEquals(listOf("copy-3"), search("014.45").map(LibraryBookRow::copyId))
        assertEquals(listOf("copy-4"), search("特別棚").map(LibraryBookRow::copyId))
        assertEquals(
            listOf("copy-1", "copy-0"),
            search(criteria = LibrarySearchCriteria(readingStatus = ReadingStatus.READING))
                .map(LibraryBookRow::copyId),
        )
    }

    @Test
    fun appliesEverySupportedSortWithStableTieBreakers() = runBlocking {
        insertBooks(
            listOf(
                sample(0, title = "Beta", author = "Alice", ndc = null, addedAt = 30),
                sample(1, title = "Alpha", author = "Carol", ndc = "913", addedAt = 20),
                sample(2, title = "Gamma", author = "Bob", ndc = "014", addedAt = 10),
            ),
        )

        assertEquals(listOf("copy-0", "copy-1", "copy-2"), ids(LibrarySort.ADDED_NEWEST))
        assertEquals(listOf("copy-1", "copy-0", "copy-2"), ids(LibrarySort.TITLE))
        assertEquals(listOf("copy-0", "copy-2", "copy-1"), ids(LibrarySort.AUTHOR))
        assertEquals(listOf("copy-2", "copy-1", "copy-0"), ids(LibrarySort.NDC))
        assertEquals(listOf("copy-2", "copy-1", "copy-0"), ids(LibrarySort.SHELF))
    }

    @Test
    fun selectedEditionModeRejectsUnknownIdsAndIncludesEveryEditionCopy() = runBlocking {
        val first = sample(0)
        insertBooks(listOf(first, sample(1)))
        dao.insertCopy(
            OwnedCopyEntity(
                id = "copy-extra",
                editionId = first.editionId,
                mediaType = "PHYSICAL",
                location = "別の棚",
                readingStatus = "READ",
                addedAt = 2,
                copyLabel = "保存用",
            ),
        )

        assertEquals(
            setOf("copy-0", "copy-extra"),
            search(criteria = LibrarySearchCriteria(selectedEditionId = first.editionId))
                .map(LibraryBookRow::copyId)
                .toSet(),
        )
        assertTrue(
            search(criteria = LibrarySearchCriteria(selectedEditionId = "missing")).isEmpty(),
        )
    }

    @Test
    fun representativeDatasetsRemainQueryable() = runBlocking {
        var inserted = 0
        listOf(1_000, 5_000, 20_000).forEach { size ->
            insertBooks((inserted until size).map { sample(it) })
            inserted = size
            var rows: List<LibraryBookRow> = emptyList()
            val elapsed = measureTimeMillis { rows = search("郷土") }
            var allRows: List<LibraryBookRow> = emptyList()
            val initialElapsed = measureTimeMillis { allRows = search() }

            assertEquals((0 until size).count { it % 97 == 0 }, rows.size)
            assertEquals(size, allRows.size)
            assertTrue("$size rows query exceeded smoke budget: ${elapsed}ms", elapsed < 5_000)
            assertTrue(
                "$size rows initial query exceeded smoke budget: ${initialElapsed}ms",
                initialElapsed < 5_000,
            )
            println("library-search-room,$size,$elapsed,$initialElapsed,${rows.size}")
        }
    }

    private suspend fun ids(sort: LibrarySort): List<String> =
        search(criteria = LibrarySearchCriteria(sort = sort)).map(LibraryBookRow::copyId)

    private suspend fun search(
        query: String = "",
        criteria: LibrarySearchCriteria = LibrarySearchCriteria(query = query),
    ): List<LibraryBookRow> = dao.observeLibrarySearch(criteria.toSQLiteQuery()).first()

    private suspend fun insertBooks(samples: List<Sample>) {
        if (samples.isEmpty()) return
        database.withTransaction {
            dao.upsertWorks(samples.map { BookWorkEntity(it.workId, it.title, it.author) })
            dao.upsertEditions(
                samples.map {
                    BookEditionEntity(
                        id = it.editionId,
                        workId = it.workId,
                        isbn13 = it.isbn,
                        publisher = "出版社",
                        publishedYear = 2024,
                        coverUrl = null,
                        ndcCode = it.ndc,
                        ndcEdition = "NDC10",
                        classificationSource = "NDL",
                    )
                },
            )
            dao.upsertCopies(
                samples.map {
                    OwnedCopyEntity(
                        id = it.copyId,
                        editionId = it.editionId,
                        mediaType = "PHYSICAL",
                        location = it.location,
                        readingStatus = it.status.name,
                        addedAt = it.addedAt,
                        shelfOrderKey = "%08d".format(it.index),
                        copyLabel = "所蔵本",
                    )
                },
            )
        }
    }

    private fun sample(
        index: Int,
        title: String = if (index % 97 == 0) "郷土資料 $index" else "資料 $index",
        author: String = "著者 ${index % 431}",
        isbn: String = "978${index.toString().padStart(10, '0')}",
        ndc: String? = "%03d".format(index % 1_000),
        location: String = "部屋${index % 8} / 棚${index % 32} / 段${index % 5}",
        status: ReadingStatus = ReadingStatus.entries[index % ReadingStatus.entries.size],
        addedAt: Long = 1_700_000_000_000L + index,
    ) = Sample(index, title, author, isbn, ndc, location, status, addedAt)

    private data class Sample(
        val index: Int,
        val title: String,
        val author: String,
        val isbn: String,
        val ndc: String?,
        val location: String,
        val status: ReadingStatus,
        val addedAt: Long,
    ) {
        val workId = "work-$index"
        val editionId = "edition-$index"
        val copyId = "copy-$index"
    }
}
