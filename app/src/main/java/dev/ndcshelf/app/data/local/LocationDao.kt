package dev.ndcshelf.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Query("SELECT * FROM location_rooms ORDER BY sortOrder, name, id")
    fun observeRooms(): Flow<List<LocationRoomEntity>>

    @Query("SELECT * FROM location_shelves ORDER BY sortOrder, name, id")
    fun observeShelves(): Flow<List<LocationShelfEntity>>

    @Query("SELECT * FROM location_tiers ORDER BY sortOrder, name, id")
    fun observeTiers(): Flow<List<LocationTierEntity>>

    @Query(
        """
        SELECT tiers.id AS tierId, COUNT(copies.id) AS copyCount
        FROM location_tiers AS tiers
        LEFT JOIN owned_copies AS copies ON copies.tierId = tiers.id
        GROUP BY tiers.id
        """,
    )
    fun observeTierCounts(): Flow<List<LocationTierCountRow>>

    @Query("SELECT * FROM location_rooms ORDER BY sortOrder, name, id")
    suspend fun getRooms(): List<LocationRoomEntity>

    @Query("SELECT * FROM location_shelves ORDER BY sortOrder, name, id")
    suspend fun getAllShelves(): List<LocationShelfEntity>

    @Query("SELECT * FROM location_tiers ORDER BY sortOrder, name, id")
    suspend fun getAllTiers(): List<LocationTierEntity>

    @Query("SELECT * FROM location_shelves WHERE roomId = :roomId ORDER BY sortOrder, name, id")
    suspend fun getShelves(roomId: String): List<LocationShelfEntity>

    @Query("SELECT * FROM location_tiers WHERE shelfId = :shelfId ORDER BY sortOrder, name, id")
    suspend fun getTiers(shelfId: String): List<LocationTierEntity>

    @Query("SELECT * FROM location_tiers WHERE id = :tierId LIMIT 1")
    suspend fun findTier(tierId: String): LocationTierEntity?

    @Query("SELECT * FROM location_rooms WHERE id = :id LIMIT 1")
    suspend fun findRoom(id: String): LocationRoomEntity?

    @Query("SELECT * FROM location_shelves WHERE id = :id LIMIT 1")
    suspend fun findShelf(id: String): LocationShelfEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRoom(room: LocationRoomEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertShelf(shelf: LocationShelfEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTier(tier: LocationTierEntity)

    @Upsert
    suspend fun upsertRooms(rooms: List<LocationRoomEntity>)

    @Upsert
    suspend fun upsertShelves(shelves: List<LocationShelfEntity>)

    @Upsert
    suspend fun upsertTiers(tiers: List<LocationTierEntity>)

    @Query("DELETE FROM location_tiers")
    suspend fun deleteAllTiers()

    @Query("DELETE FROM location_shelves")
    suspend fun deleteAllShelves()

    @Query("DELETE FROM location_rooms")
    suspend fun deleteAllRooms()

    @Query("UPDATE location_rooms SET name = :name WHERE id = :id")
    suspend fun renameRoom(id: String, name: String): Int

    @Query("UPDATE location_shelves SET name = :name WHERE id = :id")
    suspend fun renameShelf(id: String, name: String): Int

    @Query("UPDATE location_tiers SET name = :name WHERE id = :id")
    suspend fun renameTier(id: String, name: String): Int

    @Query("UPDATE location_rooms SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateRoomOrder(id: String, sortOrder: Int)

    @Query("UPDATE location_shelves SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateShelfOrder(id: String, sortOrder: Int)

    @Query("UPDATE location_tiers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateTierOrder(id: String, sortOrder: Int)

    @Query("SELECT COUNT(*) FROM owned_copies WHERE tierId IN (:tierIds)")
    suspend fun countCopiesInTiers(tierIds: List<String>): Int

    @Query("UPDATE owned_copies SET tierId = :replacementTierId, location = :fallback WHERE tierId IN (:tierIds)")
    suspend fun reassignCopies(
        tierIds: List<String>,
        replacementTierId: String?,
        fallback: String,
    )

    @Query("DELETE FROM location_rooms WHERE id = :id")
    suspend fun deleteRoom(id: String): Int

    @Query("DELETE FROM location_shelves WHERE id = :id")
    suspend fun deleteShelf(id: String): Int

    @Query("DELETE FROM location_tiers WHERE id = :id")
    suspend fun deleteTier(id: String): Int
}
