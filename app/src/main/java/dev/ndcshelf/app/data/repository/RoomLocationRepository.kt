package dev.ndcshelf.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import dev.ndcshelf.app.domain.model.LocationLevel
import dev.ndcshelf.app.domain.model.LocationMutationResult
import dev.ndcshelf.app.domain.model.LocationRoom
import dev.ndcshelf.app.domain.model.LocationShelf
import dev.ndcshelf.app.domain.model.LocationTier
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MoveDirection
import dev.ndcshelf.app.domain.repository.LocationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

class RoomLocationRepository(
    private val database: AppDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : LocationRepository {
    private val dao = database.locationDao()

    override fun observeTree(): Flow<LocationTree> = combine(
        dao.observeRooms(),
        dao.observeShelves(),
        dao.observeTiers(),
        dao.observeTierCounts(),
    ) { rooms, shelves, tiers, counts ->
        val countByTier = counts.associate { it.tierId to it.copyCount }
        val shelvesByRoom = shelves.groupBy(LocationShelfEntity::roomId)
        val tiersByShelf = tiers.groupBy(LocationTierEntity::shelfId)
        LocationTree(
            rooms.map { room ->
                LocationRoom(
                    id = room.id,
                    name = room.name,
                    sortOrder = room.sortOrder,
                    shelves = shelvesByRoom[room.id].orEmpty().map { shelf ->
                        LocationShelf(
                            id = shelf.id,
                            roomId = shelf.roomId,
                            name = shelf.name,
                            sortOrder = shelf.sortOrder,
                            tiers = tiersByShelf[shelf.id].orEmpty().map { tier ->
                                LocationTier(
                                    id = tier.id,
                                    shelfId = tier.shelfId,
                                    name = tier.name,
                                    sortOrder = tier.sortOrder,
                                    copyCount = countByTier[tier.id] ?: 0,
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    override suspend fun addRoom(name: String): LocationMutationResult = mutate(name) { value ->
        dao.insertRoom(LocationRoomEntity(idFactory(), value, dao.getRooms().size))
    }

    override suspend fun addShelf(roomId: String, name: String): LocationMutationResult =
        mutate(name) { value ->
            if (dao.findRoom(roomId) == null) return@mutate LocationMutationResult.NotFound
            dao.insertShelf(LocationShelfEntity(idFactory(), roomId, value, dao.getShelves(roomId).size))
            LocationMutationResult.Success
        }

    override suspend fun addTier(shelfId: String, name: String): LocationMutationResult =
        mutate(name) { value ->
            if (dao.findShelf(shelfId) == null) return@mutate LocationMutationResult.NotFound
            dao.insertTier(LocationTierEntity(idFactory(), shelfId, value, dao.getTiers(shelfId).size))
            LocationMutationResult.Success
        }

    override suspend fun rename(
        level: LocationLevel,
        id: String,
        name: String,
    ): LocationMutationResult = mutate(name) { value ->
        val updated = when (level) {
            LocationLevel.ROOM -> dao.renameRoom(id, value)
            LocationLevel.SHELF -> dao.renameShelf(id, value)
            LocationLevel.TIER -> dao.renameTier(id, value)
        }
        if (updated == 1) LocationMutationResult.Success else LocationMutationResult.NotFound
    }

    override suspend fun move(
        level: LocationLevel,
        id: String,
        direction: MoveDirection,
    ): LocationMutationResult = try {
        database.withTransaction {
            when (level) {
                LocationLevel.ROOM -> moveAmong(dao.getRooms(), id, direction) { item, order ->
                    dao.updateRoomOrder(item.id, order)
                }
                LocationLevel.SHELF -> {
                    val item = dao.findShelf(id) ?: return@withTransaction LocationMutationResult.NotFound
                    moveAmong(dao.getShelves(item.roomId), id, direction) { sibling, order ->
                        dao.updateShelfOrder(sibling.id, order)
                    }
                }
                LocationLevel.TIER -> {
                    val item = dao.findTier(id) ?: return@withTransaction LocationMutationResult.NotFound
                    moveAmong(dao.getTiers(item.shelfId), id, direction) { sibling, order ->
                        dao.updateTierOrder(sibling.id, order)
                    }
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        LocationMutationResult.Failure
    }

    override suspend fun delete(
        level: LocationLevel,
        id: String,
        replacementTierId: String?,
        confirmUnset: Boolean,
    ): LocationMutationResult = try {
        database.withTransaction {
            val tierIds = descendantTierIds(level, id)
                ?: return@withTransaction LocationMutationResult.NotFound
            val count = if (tierIds.isEmpty()) 0 else dao.countCopiesInTiers(tierIds)
            if (count > 0 && replacementTierId == null && !confirmUnset) {
                return@withTransaction LocationMutationResult.InUse(count)
            }
            if (replacementTierId != null &&
                (replacementTierId in tierIds || dao.findTier(replacementTierId) == null)
            ) {
                return@withTransaction LocationMutationResult.InvalidDestination
            }
            if (count > 0) dao.reassignCopies(tierIds, replacementTierId, UNSET_LOCATION)
            val deleted = when (level) {
                LocationLevel.ROOM -> dao.deleteRoom(id)
                LocationLevel.SHELF -> dao.deleteShelf(id)
                LocationLevel.TIER -> dao.deleteTier(id)
            }
            if (deleted == 1) LocationMutationResult.Success else LocationMutationResult.NotFound
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        LocationMutationResult.Failure
    }

    private suspend fun descendantTierIds(level: LocationLevel, id: String): List<String>? =
        when (level) {
            LocationLevel.ROOM -> {
                if (dao.findRoom(id) == null) null else {
                    val shelfIds = dao.getShelves(id).map(LocationShelfEntity::id).toSet()
                    dao.getAllTiers().filter { it.shelfId in shelfIds }.map(LocationTierEntity::id)
                }
            }
            LocationLevel.SHELF -> {
                if (dao.findShelf(id) == null) null else dao.getTiers(id).map(LocationTierEntity::id)
            }
            LocationLevel.TIER -> if (dao.findTier(id) == null) null else listOf(id)
        }

    private suspend fun <T> moveAmong(
        siblings: List<T>,
        id: String,
        direction: MoveDirection,
        update: suspend (T, Int) -> Unit,
    ): LocationMutationResult {
        val index = siblings.indexOfFirst {
            when (it) {
                is LocationRoomEntity -> it.id == id
                is LocationShelfEntity -> it.id == id
                is LocationTierEntity -> it.id == id
                else -> false
            }
        }
        if (index < 0) return LocationMutationResult.NotFound
        val destination = index + if (direction == MoveDirection.UP) -1 else 1
        if (destination !in siblings.indices) return LocationMutationResult.Success
        val reordered = siblings.toMutableList().apply {
            add(destination, removeAt(index))
        }
        reordered.forEachIndexed { order, item -> update(item, order) }
        return LocationMutationResult.Success
    }

    private suspend fun mutate(
        rawName: String,
        block: suspend (String) -> Any?,
    ): LocationMutationResult {
        val name = rawName.trim()
        if (name.isEmpty()) return LocationMutationResult.InvalidName("名前を入力してください")
        if (name.length > MAX_NAME_LENGTH) {
            return LocationMutationResult.InvalidName("${MAX_NAME_LENGTH}文字以下にしてください")
        }
        if ('\u0000' in name || '/' in name) {
            return LocationMutationResult.InvalidName("NUL文字と / は使用できません")
        }
        return try {
            when (val result = block(name)) {
                is LocationMutationResult -> result
                else -> LocationMutationResult.Success
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SQLiteConstraintException) {
            LocationMutationResult.DuplicateName
        } catch (_: Exception) {
            LocationMutationResult.Failure
        }
    }

    companion object {
        private const val MAX_NAME_LENGTH = 100
        private const val UNSET_LOCATION = "未設定"
    }
}
