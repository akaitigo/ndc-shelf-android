package dev.ndcshelf.app.domain.model

data class LocationTree(
    val rooms: List<LocationRoom> = emptyList(),
) {
    val tiers: List<LocationTier>
        get() = rooms.flatMap { room -> room.shelves.flatMap(LocationShelf::tiers) }

    fun pathForTier(tierId: String): String? {
        rooms.forEach { room ->
            room.shelves.forEach { shelf ->
                shelf.tiers.firstOrNull { it.id == tierId }?.let { tier ->
                    return "${room.name} / ${shelf.name} / ${tier.name}"
                }
            }
        }
        return null
    }
}

data class LocationRoom(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val shelves: List<LocationShelf>,
)

data class LocationShelf(
    val id: String,
    val roomId: String,
    val name: String,
    val sortOrder: Int,
    val tiers: List<LocationTier>,
)

data class LocationTier(
    val id: String,
    val shelfId: String,
    val name: String,
    val sortOrder: Int,
    val copyCount: Int,
)

enum class LocationLevel { ROOM, SHELF, TIER }

enum class MoveDirection { UP, DOWN }

sealed interface LocationMutationResult {
    data object Success : LocationMutationResult
    data class InvalidName(val reason: String) : LocationMutationResult
    data object DuplicateName : LocationMutationResult
    data object NotFound : LocationMutationResult
    data class InUse(val copyCount: Int) : LocationMutationResult
    data object InvalidDestination : LocationMutationResult
    data object Failure : LocationMutationResult
}
