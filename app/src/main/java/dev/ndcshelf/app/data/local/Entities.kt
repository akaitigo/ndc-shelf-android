package dev.ndcshelf.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "book_works")
data class BookWorkEntity(
    @PrimaryKey val id: String,
    val title: String,
    val primaryAuthor: String,
)

@Entity(
    tableName = "book_editions",
    foreignKeys = [
        ForeignKey(
            entity = BookWorkEntity::class,
            parentColumns = ["id"],
            childColumns = ["workId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["workId"]),
        Index(value = ["isbn13"], unique = true),
    ],
)
data class BookEditionEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val isbn13: String,
    val publisher: String?,
    val publishedYear: Int?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: String,
)

@Entity(
    tableName = "location_rooms",
    indices = [Index(value = ["name"], unique = true)],
)
data class LocationRoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "location_shelves",
    foreignKeys = [
        ForeignKey(
            entity = LocationRoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["roomId"]),
        Index(value = ["roomId", "name"], unique = true),
    ],
)
data class LocationShelfEntity(
    @PrimaryKey val id: String,
    val roomId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "location_tiers",
    foreignKeys = [
        ForeignKey(
            entity = LocationShelfEntity::class,
            parentColumns = ["id"],
            childColumns = ["shelfId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["shelfId"]),
        Index(value = ["shelfId", "name"], unique = true),
    ],
)
data class LocationTierEntity(
    @PrimaryKey val id: String,
    val shelfId: String,
    val name: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "owned_copies",
    foreignKeys = [
        ForeignKey(
            entity = BookEditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["editionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocationTierEntity::class,
            parentColumns = ["id"],
            childColumns = ["tierId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["editionId"]),
        Index(value = ["tierId"]),
        Index(value = ["tierId", "shelfOrderKey"]),
    ],
)
data class OwnedCopyEntity(
    @PrimaryKey val id: String,
    val editionId: String,
    val mediaType: String,
    val location: String,
    val readingStatus: String,
    val addedAt: Long,
    val tierId: String? = null,
    val shelfOrderKey: String? = null,
    val copyLabel: String = "所蔵本",
)

@Entity(
    tableName = "wishlist_items",
    foreignKeys = [
        ForeignKey(
            entity = BookEditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["editionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["status"])],
)
data class WishlistItemEntity(
    @PrimaryKey val editionId: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "scan_sessions",
    indices = [Index(value = ["endedAt"])],
)
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
)

@Entity(
    tableName = "scan_attempts",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["copyId"]),
        Index(value = ["attemptedAt"]),
    ],
)
data class ScanAttemptEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val isbn: String,
    val outcome: String,
    val copyId: String?,
    val copySnapshot: String?,
    val attemptedAt: Long,
    val undoneAt: Long?,
)

data class ScanSessionAttemptRow(
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val attemptId: String?,
    val isbn: String?,
    val outcome: String?,
    val copyId: String?,
    val attemptedAt: Long?,
    val undoneAt: Long?,
)

data class WishlistBookRow(
    val workId: String,
    val editionId: String,
    val title: String,
    val primaryAuthor: String,
    val isbn13: String,
    val publisher: String?,
    val publishedYear: Int?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ownedCopyCount: Int,
)

data class LibraryBookRow(
    val copyId: String,
    val workId: String,
    val editionId: String,
    val title: String,
    val primaryAuthor: String,
    val isbn13: String,
    val publisher: String?,
    val publishedYear: Int?,
    val coverUrl: String?,
    val ndcCode: String?,
    val ndcEdition: String?,
    val classificationSource: String,
    val mediaType: String,
    val location: String,
    val locationTierId: String?,
    val readingStatus: String,
    val addedAt: Long,
    val shelfOrderKey: String?,
    val copyLabel: String,
)

data class LocationTierCountRow(
    val tierId: String,
    val copyCount: Int,
)
