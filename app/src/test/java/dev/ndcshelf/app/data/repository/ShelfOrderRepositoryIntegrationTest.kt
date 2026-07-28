package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.remote.BookMetadataLookupResult
import dev.ndcshelf.app.data.remote.BookMetadataService
import dev.ndcshelf.app.domain.model.BookEditDraft
import dev.ndcshelf.app.domain.model.ReadingStatus
import dev.ndcshelf.app.domain.repository.ShelfMoveDirection
import dev.ndcshelf.app.domain.repository.ShelfMoveResult
import dev.ndcshelf.app.domain.repository.UpdateBookResult
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
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfOrderRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: DefaultLibraryRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultLibraryRepository(
            database,
            BookMetadataService { BookMetadataLookupResult.NotFound },
        )
        seedLibrary()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun placeAfterAndAccessibleMoveOnlyRewriteTheMovedCopy() = runBlocking {
        val result = repository.updateBook(
            "copy-b",
            BookEditDraft(
                title = "本B",
                primaryAuthor = "著者",
                publisher = "",
                publishedYear = "",
                ndcCode = "",
                ndcEdition = "",
                location = "書斎 / 本棚 / 下段",
                readingStatus = ReadingStatus.UNREAD,
                locationTierId = "tier-2",
                locationInsertAfterCopyId = "copy-d",
                locationPositionSpecified = true,
            ),
        )
        assert(result is UpdateBookResult.Updated)
        assertEquals(
            listOf("copy-d", "copy-b", "copy-e"),
            database.locationDao().getOrderedCopies("tier-2").map { it.id },
        )
        val fixedKeys = database.locationDao().getOrderedCopies("tier-2")
            .filterNot { it.id == "copy-b" }
            .associate { it.id to it.shelfOrderKey }
        val beforeMove = database.libraryDao().findCopyById("copy-b")?.shelfOrderKey

        assertSame(
            ShelfMoveResult.Moved,
            repository.moveBookWithinTier("copy-b", ShelfMoveDirection.RIGHT),
        )

        assertEquals(
            listOf("copy-d", "copy-e", "copy-b"),
            database.locationDao().getOrderedCopies("tier-2").map { it.id },
        )
        assertNotEquals(beforeMove, database.libraryDao().findCopyById("copy-b")?.shelfOrderKey)
        assertEquals(
            fixedKeys,
            database.locationDao().getOrderedCopies("tier-2")
                .filterNot { it.id == "copy-b" }
                .associate { it.id to it.shelfOrderKey },
        )
    }

    @Test
    fun movingCompactsAnOverlongTierBeforeCreatingTheNextKey() = runBlocking {
        database.locationDao().updateCopyOrder("copy-b", "40" + "00".repeat(32))

        assertSame(
            ShelfMoveResult.Moved,
            repository.moveBookWithinTier("copy-b", ShelfMoveDirection.RIGHT),
        )

        val ordered = database.locationDao().getOrderedCopies("tier-1")
        assertEquals(listOf("copy-a", "copy-c", "copy-b"), ordered.map { it.id })
        val keys = ordered.mapNotNull { it.shelfOrderKey }
        assertEquals(ordered.size, keys.size)
        assertTrue(keys.maxOf(String::length) <= FractionalOrderKey.MAX_GENERATED_LENGTH)
    }

    @Test
    fun movingOneCopyInTenThousandCompletesWithinBudget() = runBlocking {
        val copies = (0 until 10_000).map { index ->
            OwnedCopyEntity(
                id = "large-${index.toString().padStart(5, '0')}",
                editionId = "edition",
                mediaType = "PHYSICAL",
                location = "階層",
                readingStatus = "UNREAD",
                addedAt = index.toLong(),
                tierId = "tier-1",
                shelfOrderKey = index.toString(16).padStart(8, '0'),
            )
        }
        database.libraryDao().upsertCopies(copies)

        val elapsed = measureTimeMillis {
            assertSame(
                ShelfMoveResult.Moved,
                repository.moveBookWithinTier("large-05000", ShelfMoveDirection.RIGHT),
            )
        }

        val ordered = database.locationDao().getOrderedCopies("tier-1").map { it.id }
        val movedIndex = ordered.indexOf("large-05000")
        assertEquals("large-05001", ordered[movedIndex - 1])
        assertTrue("10,000冊の移動に${elapsed}ms", elapsed < 3_000)
    }

    private suspend fun seedLibrary() {
        val library = database.libraryDao()
        val locations = database.locationDao()
        library.insertWork(BookWorkEntity("work", "本B", "著者"))
        library.insertEdition(
            BookEditionEntity(
                "edition", "work", "9784101010014", null, null, null,
                null, null, "UNKNOWN",
            ),
        )
        locations.insertRoom(LocationRoomEntity("room", "書斎", 0))
        locations.insertShelf(LocationShelfEntity("shelf", "room", "本棚", 0))
        locations.insertTier(LocationTierEntity("tier-1", "shelf", "上段", 0))
        locations.insertTier(LocationTierEntity("tier-2", "shelf", "下段", 1))
        listOf(
            Triple("copy-a", "tier-1", "20"),
            Triple("copy-b", "tier-1", "40"),
            Triple("copy-c", "tier-1", "60"),
            Triple("copy-d", "tier-2", "20"),
            Triple("copy-e", "tier-2", "60"),
        ).forEachIndexed { index, (id, tier, order) ->
            library.insertCopy(
                OwnedCopyEntity(
                    id = id,
                    editionId = "edition",
                    mediaType = "PHYSICAL",
                    location = "階層",
                    readingStatus = "UNREAD",
                    addedAt = index.toLong(),
                    tierId = tier,
                    shelfOrderKey = order,
                ),
            )
        }
    }
}
