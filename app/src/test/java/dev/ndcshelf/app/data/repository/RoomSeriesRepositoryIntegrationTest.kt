package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.SeriesMembershipEntity
import dev.ndcshelf.app.data.local.WishlistItemEntity
import dev.ndcshelf.app.domain.model.SeriesVolumeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSeriesRepositoryIntegrationTest {
    private lateinit var database: AppDatabase

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
    fun catalogAggregatesDistinctCopiesWishlistOrderingAndEmptySeries() = runBlocking {
        val seriesDao = database.seriesDao()
        val libraryDao = database.libraryDao()
        seriesDao.upsertSeriesItems(
            listOf(
                SeriesEntity("series", "長編", 1, 20),
                SeriesEntity("empty", "空のシリーズ", 1, 10),
            ),
        )
        libraryDao.upsertWorks(
            listOf(
                BookWorkEntity("work-1", "第一巻", "著者"),
                BookWorkEntity("work-2", "第二巻", "著者"),
                BookWorkEntity("work-side", "外伝", "著者"),
            ),
        )
        seriesDao.upsertMemberships(
            listOf(
                membership("member-1", "work-1", "10", "1巻", "MAIN_STORY"),
                membership("member-2", "work-2", "20", "2巻", "MAIN_STORY"),
                membership("member-side", "work-side", "30", "外伝", "SIDE_STORY"),
            ),
        )
        libraryDao.upsertEditions(
            listOf(
                edition("edition-1a", "work-1", "9784000000015"),
                edition("edition-1b", "work-1", "9784000000022"),
                edition("edition-2", "work-2", "9784000000039"),
                edition("edition-side", "work-side", "9784000000046"),
            ),
        )
        libraryDao.upsertCopies(
            listOf(
                copy("copy-read", "edition-1a", "READ", 100),
                copy("copy-reading", "edition-1b", "READING", 200),
            ),
        )
        libraryDao.upsertWishlistItems(
            listOf(
                WishlistItemEntity("edition-1a", "WANTED", 1, 2),
                WishlistItemEntity("edition-2", "RESERVED", 1, 3),
                WishlistItemEntity("edition-side", "WANTED", 1, 4),
            ),
        )

        val catalog = RoomSeriesRepository(database).observeCatalog().first()

        assertEquals(listOf("empty", "series"), catalog.map { it.series.id })
        assertTrue(catalog.first().volumes.isEmpty())
        val overview = catalog.last()
        assertEquals(listOf("1巻", "2巻", "外伝"), overview.volumes.map { it.membership.volumeLabel })
        assertEquals(3, overview.knownVolumeCount)
        assertEquals(1, overview.ownedVolumeCount)
        assertEquals(1, overview.readVolumeCount)
        assertEquals(1, overview.missingCandidateCount)

        val owned = overview.volumes[0]
        assertEquals(2, owned.ownedCopyCount)
        assertEquals(1, owned.readCopyCount)
        assertEquals(1, owned.readingCopyCount)
        assertEquals(SeriesVolumeState.OWNED, owned.state)
        assertEquals(200L, owned.latestOwnedAddedAt)

        val reserved = overview.volumes[1]
        assertEquals(SeriesVolumeState.RESERVED, reserved.state)
        assertTrue(reserved.isMissingCandidate)
        assertEquals("9784000000039", reserved.bookstoreIsbn)

        val sideStory = overview.volumes[2]
        assertEquals(SeriesVolumeState.WANTED, sideStory.state)
        assertFalse(sideStory.isMissingCandidate)
        assertNull(sideStory.ownedEditionId)
    }

    private fun membership(
        id: String,
        workId: String,
        order: String,
        label: String,
        type: String,
    ) = SeriesMembershipEntity(id, "series", workId, order, label, type, 1, 2)

    private fun edition(id: String, workId: String, isbn: String) = BookEditionEntity(
        id = id,
        workId = workId,
        isbn13 = isbn,
        publisher = null,
        publishedYear = null,
        coverUrl = null,
        ndcCode = null,
        ndcEdition = null,
        classificationSource = "UNKNOWN",
        bibliographicSource = "MANUAL",
    )

    private fun copy(id: String, editionId: String, status: String, addedAt: Long) =
        OwnedCopyEntity(
            id = id,
            editionId = editionId,
            mediaType = "PHYSICAL",
            location = "未設定",
            readingStatus = status,
            addedAt = addedAt,
        )
}
