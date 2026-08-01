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
import dev.ndcshelf.app.data.local.WorkGroupEntity
import dev.ndcshelf.app.data.local.WorkGroupMembershipEntity
import dev.ndcshelf.app.domain.model.SeriesVolumeState
import dev.ndcshelf.app.domain.model.SeriesMembershipOrigin
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import dev.ndcshelf.app.domain.repository.SeriesConfirmationDraft
import dev.ndcshelf.app.domain.repository.SeriesConfirmationResult
import dev.ndcshelf.app.domain.repository.SeriesConfirmationTarget
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

    @Test
    fun suggestionsRemainTransientUntilBatchConfirmationAndRemovalPreservesBooks() = runBlocking {
        database.libraryDao().upsertWorks(
            listOf(
                BookWorkEntity("work-1", "年代記 1巻", "著者"),
                BookWorkEntity("work-2", "年代記 2巻", "著者"),
                BookWorkEntity("manual", "名前に巻がない本", "著者"),
            ),
        )
        val ids = ArrayDeque(listOf("series-new", "membership-1", "membership-2"))
        val repository = RoomSeriesRepository(database, idFactory = { ids.removeFirst() }, nowMillis = { 50 })

        val suggestions = repository.observeSuggestions().first()
        assertEquals(listOf("work-1", "work-2"), suggestions.map { it.workId })
        assertTrue(database.seriesDao().getAllSeries().isEmpty())
        assertTrue(database.seriesDao().getAllMemberships().isEmpty())

        val result = repository.confirm(
            SeriesConfirmationTarget.New("年代記"),
            suggestions.map { suggestion ->
                SeriesConfirmationDraft(
                    workId = suggestion.workId,
                    volumeLabel = requireNotNull(suggestion.proposedVolumeLabel),
                    type = suggestion.proposedType,
                    sourceTitle = suggestion.sourceTitle,
                    origin = SeriesMembershipOrigin.TITLE_SUGGESTION,
                )
            },
        )

        assertEquals(
            SeriesConfirmationResult.Confirmed(
                "series-new",
                listOf("membership-1", "membership-2"),
            ),
            result,
        )
        val memberships = database.seriesDao().getAllMemberships().sortedBy { it.sortOrderKey }
        assertEquals(listOf("1巻", "2巻"), memberships.map { it.volumeLabel })
        assertTrue(memberships[0].sortOrderKey < memberships[1].sortOrderKey)
        assertTrue(memberships.all { it.origin == "TITLE_SUGGESTION" && it.confirmedBy == "USER" })
        assertEquals(listOf("年代記 1巻", "年代記 2巻"), memberships.map { it.sourceTitle })
        assertEquals(emptyList<String>(), repository.observeSuggestions().first().map { it.workId })

        assertTrue(repository.removeMembership("membership-1"))
        assertEquals(3, database.libraryDao().getAllWorks().size)
        assertEquals(listOf("membership-2"), database.seriesDao().getAllMemberships().map { it.id })

        val manual = requireNotNull(repository.suggestionFor("manual"))
        assertNull(manual.proposedVolumeLabel)
        assertEquals(SeriesMembershipType.OTHER, manual.proposedType)
    }

    @Test
    fun existingSeriesCanBeSelectedButDuplicateNameAndStaleTitleFailClosed() = runBlocking {
        database.libraryDao().upsertWorks(listOf(BookWorkEntity("work", "作品 1巻", "著者")))
        database.seriesDao().upsertSeries(SeriesEntity("existing", "作品", 1, 1))
        val repository = RoomSeriesRepository(database, idFactory = { "generated" }, nowMillis = { 2 })
        val draft = SeriesConfirmationDraft(
            workId = "work",
            volumeLabel = "1巻",
            type = SeriesMembershipType.MAIN_STORY,
            sourceTitle = "古い題名 1巻",
            origin = SeriesMembershipOrigin.TITLE_SUGGESTION,
        )

        assertEquals(
            SeriesConfirmationResult.Conflict,
            repository.confirm(SeriesConfirmationTarget.Existing("existing"), listOf(draft)),
        )
        assertEquals(
            SeriesConfirmationResult.Conflict,
            repository.confirm(SeriesConfirmationTarget.New("別シリーズ"), listOf(draft)),
        )
        assertEquals(
            SeriesConfirmationResult.Conflict,
            repository.confirm(
                SeriesConfirmationTarget.New("作品"),
                listOf(draft.copy(sourceTitle = "作品 1巻")),
            ),
        )
        assertTrue(database.seriesDao().getAllMemberships().isEmpty())
        assertEquals(listOf("existing"), database.seriesDao().getAllSeries().map { it.id })
    }

    @Test
    fun alternateEditionCountsOnlyWhenGroupSubstitutionIsEnabled() = runBlocking {
        val libraryDao = database.libraryDao()
        libraryDao.upsertWorks(
            listOf(
                BookWorkEntity("series-work", "作品 単行本", "著者"),
                BookWorkEntity("alternate-work", "作品 文庫版", "著者"),
            ),
        )
        database.seriesDao().upsertSeries(SeriesEntity("series", "作品", 1, 1))
        database.seriesDao().upsertMemberships(
            listOf(membership("membership", "series-work", "10", "1巻", "MAIN_STORY")),
        )
        libraryDao.upsertEditions(
            listOf(edition("alternate-edition", "alternate-work", "9784000000015")),
        )
        libraryDao.upsertCopies(listOf(copy("alternate-copy", "alternate-edition", "READ", 10)))
        database.workGroupDao().upsertGroups(
            listOf(WorkGroupEntity("group", "作品", "著者", false, 1, 1)),
        )
        database.workGroupDao().upsertMemberships(
            listOf(
                WorkGroupMembershipEntity("group-member-a", "group", "series-work", 1),
                WorkGroupMembershipEntity("group-member-b", "group", "alternate-work", 1),
            ),
        )
        val repository = RoomSeriesRepository(database)

        assertEquals(0, repository.observeCatalog().first().single().ownedVolumeCount)

        database.workGroupDao().updateSeriesSubstitution("group", true, 2)

        val substituted = repository.observeCatalog().first().single().volumes.single()
        assertEquals(1, substituted.ownedCopyCount)
        assertEquals(1, substituted.readCopyCount)
        assertEquals("alternate-edition", substituted.ownedEditionId)
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
