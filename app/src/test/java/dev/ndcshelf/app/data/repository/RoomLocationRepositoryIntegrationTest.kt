package dev.ndcshelf.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.domain.model.LocationLevel
import dev.ndcshelf.app.domain.model.LocationMutationResult
import dev.ndcshelf.app.domain.model.MoveDirection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLocationRepositoryIntegrationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomLocationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var nextId = 0
        repository = RoomLocationRepository(database) { "location-${nextId++}" }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun emptyNodesRemainVisibleAndNamesAreUniqueWithinTheirParent() = runBlocking {
        assertSame(LocationMutationResult.Success, repository.addRoom("書斎"))
        assertSame(LocationMutationResult.DuplicateName, repository.addRoom(" 書斎 "))
        assertSame(LocationMutationResult.Success, repository.addRoom("居間"))
        val rooms = database.locationDao().getRooms().associateBy { it.name }
        assertSame(LocationMutationResult.Success, repository.addShelf(requireNotNull(rooms["書斎"]).id, "本棚A"))
        assertSame(LocationMutationResult.Success, repository.addShelf(requireNotNull(rooms["居間"]).id, "本棚A"))

        val tree = repository.observeTree().first { it.rooms.size == 2 }

        assertEquals(listOf("書斎", "居間"), tree.rooms.map { it.name })
        assertEquals("本棚A", tree.rooms[0].shelves.single().name)
        assertEquals(emptyList<Any>(), tree.rooms[0].shelves.single().tiers)
    }

    @Test
    fun moveUsesSiblingOrderOnly() = runBlocking {
        repository.addRoom("書斎")
        repository.addRoom("居間")
        repository.addRoom("寝室")

        assertSame(
            LocationMutationResult.Success,
            repository.move(LocationLevel.ROOM, "location-2", MoveDirection.UP),
        )

        assertEquals(listOf("書斎", "寝室", "居間"), database.locationDao().getRooms().map { it.name })
    }

    @Test
    fun deletingUsedTierRequiresDecisionAndCanReassignCopies() = runBlocking {
        repository.addRoom("書斎")
        repository.addShelf("location-0", "本棚")
        repository.addTier("location-1", "上段")
        repository.addTier("location-1", "下段")
        insertBook(tierId = "location-2")

        assertEquals(
            LocationMutationResult.InUse(1),
            repository.delete(LocationLevel.TIER, "location-2"),
        )
        assertSame(
            LocationMutationResult.Success,
            repository.delete(
                LocationLevel.TIER,
                "location-2",
                replacementTierId = "location-3",
            ),
        )

        assertEquals("location-3", database.libraryDao().findCopyById("copy")?.tierId)
        assertNotNull(database.libraryDao().findCopyById("copy")?.shelfOrderKey)
        assertEquals("書斎 / 本棚 / 下段", database.libraryDao().findOwnedByCopyId("copy")?.location)
    }

    @Test
    fun deletingUsedRoomCanExplicitlyUnsetWithoutParsingLegacyLocation() = runBlocking {
        repository.addRoom("書斎")
        repository.addShelf("location-0", "本棚")
        repository.addTier("location-1", "上段")
        insertBook(tierId = "location-2", location = "以前の自由入力")

        assertSame(
            LocationMutationResult.Success,
            repository.delete(LocationLevel.ROOM, "location-0", confirmUnset = true),
        )

        val copy = database.libraryDao().findCopyById("copy")
        assertNull(copy?.tierId)
        assertEquals("未設定", copy?.location)
    }

    private suspend fun insertBook(tierId: String, location: String = "以前の自由入力") {
        val dao = database.libraryDao()
        dao.insertWork(BookWorkEntity("work", "本", "著者"))
        dao.insertEdition(
            BookEditionEntity(
                id = "edition",
                workId = "work",
                isbn13 = "9784101010014",
                publisher = null,
                publishedYear = null,
                coverUrl = null,
                ndcCode = null,
                ndcEdition = null,
                classificationSource = "UNKNOWN",
            ),
        )
        dao.insertCopy(
            OwnedCopyEntity(
                id = "copy",
                editionId = "edition",
                mediaType = "PHYSICAL",
                location = location,
                readingStatus = "UNREAD",
                addedAt = 1,
                tierId = tierId,
            ),
        )
    }
}
