package dev.ndcshelf.app.domain.repository

import dev.ndcshelf.app.domain.model.LocationLevel
import dev.ndcshelf.app.domain.model.LocationMutationResult
import dev.ndcshelf.app.domain.model.LocationTree
import dev.ndcshelf.app.domain.model.MoveDirection
import kotlinx.coroutines.flow.Flow

interface LocationRepository {
    fun observeTree(): Flow<LocationTree>

    suspend fun addRoom(name: String): LocationMutationResult
    suspend fun addShelf(roomId: String, name: String): LocationMutationResult
    suspend fun addTier(shelfId: String, name: String): LocationMutationResult
    suspend fun rename(level: LocationLevel, id: String, name: String): LocationMutationResult
    suspend fun move(level: LocationLevel, id: String, direction: MoveDirection): LocationMutationResult

    suspend fun delete(
        level: LocationLevel,
        id: String,
        replacementTierId: String? = null,
        confirmUnset: Boolean = false,
    ): LocationMutationResult
}
