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
    tableName = "owned_copies",
    foreignKeys = [
        ForeignKey(
            entity = BookEditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["editionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["editionId"])],
)
data class OwnedCopyEntity(
    @PrimaryKey val id: String,
    val editionId: String,
    val mediaType: String,
    val location: String,
    val readingStatus: String,
    val addedAt: Long,
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
    val readingStatus: String,
    val addedAt: Long,
)
