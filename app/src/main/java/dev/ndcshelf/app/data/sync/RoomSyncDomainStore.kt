package dev.ndcshelf.app.data.sync

import dev.ndcshelf.app.data.local.AppDatabase
import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.ReadingSessionEntity
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.SeriesMembershipEntity
import dev.ndcshelf.app.data.local.WishlistItemEntity
import dev.ndcshelf.app.data.local.WorkGroupEntity
import dev.ndcshelf.app.data.local.WorkGroupMembershipEntity
import dev.ndcshelf.app.domain.sync.SyncDomainApplyResult
import dev.ndcshelf.app.domain.sync.SyncDomainStore
import dev.ndcshelf.app.domain.sync.SyncEntityReference
import dev.ndcshelf.app.domain.sync.SyncResolvedEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class RoomSyncDomainStore(
    private val database: AppDatabase,
) : SyncDomainStore {
    override suspend fun applyUpserts(entities: List<SyncResolvedEntity>): SyncDomainApplyResult {
        val ordered = entities.sortedBy { UPSERT_ORDER.indexOf(it.entityType) }
        ordered.forEach { entity ->
            when (entity.entityType) {
                "work" -> {
                    database.libraryDao().upsertWorks(listOf(entity.toWork()))
                }

                "edition" -> {
                    database.libraryDao().upsertEditions(listOf(entity.toEdition()))
                }

                "ownedCopy" -> {
                    database.libraryDao().upsertCopies(listOf(entity.toCopy()))
                }

                "wishlistItem" -> {
                    database.libraryDao().upsertWishlistItems(listOf(entity.toWishlist()))
                }

                "locationRoom" -> {
                    database.locationDao().upsertRooms(listOf(entity.toRoom()))
                }

                "locationShelf" -> {
                    database.locationDao().upsertShelves(listOf(entity.toShelf()))
                }

                "locationTier" -> {
                    database.locationDao().upsertTiers(listOf(entity.toTier()))
                }

                "series" -> {
                    database.seriesDao().upsertSeries(entity.toSeries())
                }

                "seriesMembership" -> {
                    database.seriesDao().upsertMemberships(listOf(entity.toSeriesMembership()))
                }

                "workGroup" -> {
                    database.workGroupDao().upsertGroups(listOf(entity.toWorkGroup()))
                }

                "workGroupMembership" -> {
                    database.workGroupDao().upsertMemberships(
                        listOf(entity.toWorkGroupMembership()),
                    )
                }

                "readingSession" -> {
                    database.readingSessionDao().upsert(entity.toReadingSession())
                }

                else -> {
                    error("Unsupported sync entity type")
                }
            }
        }
        return SyncDomainApplyResult.Applied
    }

    override suspend fun applyDeletes(entities: List<SyncEntityReference>): SyncDomainApplyResult {
        entities.sortedBy { DELETE_ORDER.indexOf(it.entityType) }.forEach { entity ->
            when (entity.entityType) {
                "readingSession" -> database.readingSessionDao().deleteById(entity.entityId)
                "workGroupMembership" -> database.workGroupDao().deleteMembership(entity.entityId)
                "seriesMembership" -> database.seriesDao().deleteMembership(entity.entityId)
                "ownedCopy" -> database.libraryDao().deleteCopyById(entity.entityId)
                "wishlistItem" -> database.libraryDao().deleteWishlistByEditionId(entity.entityId)
                "edition" -> database.libraryDao().deleteEditionById(entity.entityId)
                "locationTier" -> database.locationDao().deleteTier(entity.entityId)
                "locationShelf" -> database.locationDao().deleteShelf(entity.entityId)
                "locationRoom" -> database.locationDao().deleteRoom(entity.entityId)
                "workGroup" -> database.workGroupDao().deleteGroup(entity.entityId)
                "series" -> database.seriesDao().deleteSeries(entity.entityId)
                "work" -> database.libraryDao().deleteWorkById(entity.entityId)
                else -> error("Unsupported sync entity type")
            }
        }
        return SyncDomainApplyResult.Applied
    }

    private companion object {
        val UPSERT_ORDER =
            listOf(
                "work",
                "locationRoom",
                "locationShelf",
                "locationTier",
                "edition",
                "series",
                "workGroup",
                "ownedCopy",
                "wishlistItem",
                "seriesMembership",
                "workGroupMembership",
                "readingSession",
            )
        val DELETE_ORDER = UPSERT_ORDER.reversed()
    }
}

private fun SyncResolvedEntity.toWork() =
    BookWorkEntity(
        id = entityId,
        title = fields.requiredString("title", 1_000),
        primaryAuthor = fields.requiredString("primaryAuthor", 1_000),
    )

private fun SyncResolvedEntity.toEdition() =
    BookEditionEntity(
        id = entityId,
        workId = fields.requiredString("workId", 200),
        isbn13 = fields.optionalString("isbn13", 13),
        publisher = fields.optionalString("publisher", 1_000),
        publishedYear = fields.optionalInt("publishedYear"),
        coverUrl = fields.optionalString("coverUrl", 2_048),
        ndcCode = fields.optionalString("ndcCode", 100),
        ndcEdition = fields.optionalString("ndcEdition", 100),
        classificationSource =
            fields.requiredEnum(
                "classificationSource",
                setOf("NDL", "MANUAL", "UNKNOWN"),
            ),
        bibliographicSource = fields.requiredEnum("bibliographicSource", setOf("NDL", "MANUAL")),
    )

private fun SyncResolvedEntity.toCopy() =
    OwnedCopyEntity(
        id = entityId,
        editionId = fields.requiredString("editionId", 200),
        mediaType = fields.requiredEnum("mediaType", setOf("PHYSICAL", "DIGITAL")),
        location = fields.requiredString("location", 1_000),
        readingStatus =
            fields.requiredEnum(
                "readingStatus",
                setOf("UNREAD", "READING", "READ", "PAUSED"),
            ),
        addedAt = fields.requiredLong("addedAt"),
        tierId = fields.optionalString("tierId", 200),
        shelfOrderKey = fields.optionalString("shelfOrderKey", 500),
        copyLabel = fields.requiredString("copyLabel", 500),
    )

private fun SyncResolvedEntity.toWishlist() =
    WishlistItemEntity(
        editionId = entityId,
        status = fields.requiredEnum("status", setOf("WANTED", "RESERVED")),
        createdAt = fields.requiredLong("createdAt"),
        updatedAt = fields.requiredLong("updatedAt"),
    )

private fun SyncResolvedEntity.toRoom() =
    LocationRoomEntity(
        id = entityId,
        name = fields.requiredString("name", 500),
        sortOrder = fields.requiredInt("sortOrder"),
    )

private fun SyncResolvedEntity.toShelf() =
    LocationShelfEntity(
        id = entityId,
        roomId = fields.requiredString("roomId", 200),
        name = fields.requiredString("name", 500),
        sortOrder = fields.requiredInt("sortOrder"),
    )

private fun SyncResolvedEntity.toTier() =
    LocationTierEntity(
        id = entityId,
        shelfId = fields.requiredString("shelfId", 200),
        name = fields.requiredString("name", 500),
        sortOrder = fields.requiredInt("sortOrder"),
    )

private fun SyncResolvedEntity.toSeries() =
    SeriesEntity(
        id = entityId,
        name = fields.requiredString("name", 1_000),
        createdAt = fields.requiredLong("createdAt"),
        updatedAt = fields.requiredLong("updatedAt"),
    )

private fun SyncResolvedEntity.toSeriesMembership() =
    SeriesMembershipEntity(
        id = entityId,
        seriesId = fields.requiredString("seriesId", 200),
        workId = fields.requiredString("workId", 200),
        sortOrderKey = fields.requiredString("sortOrderKey", 500),
        volumeLabel = fields.requiredString("volumeLabel", 500),
        type = fields.requiredEnum("type", setOf("MAIN_STORY", "SIDE_STORY", "OMNIBUS", "OTHER")),
        createdAt = fields.requiredLong("createdAt"),
        updatedAt = fields.requiredLong("updatedAt"),
        origin = fields.requiredEnum("origin", setOf("MANUAL", "TITLE_SUGGESTION")),
        confirmedBy = fields.requiredEnum("confirmedBy", setOf("USER")),
        sourceTitle = fields.requiredString("sourceTitle", 1_000, allowEmpty = true),
    )

private fun SyncResolvedEntity.toWorkGroup() =
    WorkGroupEntity(
        id = entityId,
        title = fields.requiredString("title", 1_000),
        primaryAuthor = fields.requiredString("primaryAuthor", 1_000),
        seriesSubstitutionEnabled = fields.requiredBoolean("seriesSubstitutionEnabled"),
        createdAt = fields.requiredLong("createdAt"),
        updatedAt = fields.requiredLong("updatedAt"),
    )

private fun SyncResolvedEntity.toWorkGroupMembership() =
    WorkGroupMembershipEntity(
        id = entityId,
        groupId = fields.requiredString("groupId", 200),
        workId = fields.requiredString("workId", 200),
        createdAt = fields.requiredLong("createdAt"),
    )

private fun SyncResolvedEntity.toReadingSession() =
    ReadingSessionEntity(
        id = entityId,
        copyId = fields.requiredString("copyId", 200),
        status = fields.requiredEnum("status", setOf("READING", "PAUSED", "FINISHED")),
        startedDay = fields.optionalString("startedDay", 10),
        finishedDay = fields.optionalString("finishedDay", 10),
        rating = fields.optionalInt("rating"),
        note = fields.optionalString("note", 2_000),
        createdAt = fields.requiredLong("createdAt"),
        updatedAt = fields.requiredLong("updatedAt"),
    )

private fun Map<String, JsonElement>.requiredString(
    name: String,
    maxLength: Int,
    allowEmpty: Boolean = false,
): String =
    requireNotNull(get(name)?.jsonPrimitive?.contentOrNull)
        .also { value -> require((allowEmpty || value.isNotBlank()) && value.length <= maxLength) }

private fun Map<String, JsonElement>.optionalString(
    name: String,
    maxLength: Int,
): String? {
    val element = get(name) ?: return null
    if (element === JsonNull) return null
    return element.jsonPrimitive.contentOrNull?.also { value -> require(value.length <= maxLength) }
}

private fun Map<String, JsonElement>.requiredLong(name: String): Long = requireNotNull(get(name)?.jsonPrimitive?.longOrNull)

private fun Map<String, JsonElement>.requiredInt(name: String): Int = requireNotNull(get(name)?.jsonPrimitive?.intOrNull)

private fun Map<String, JsonElement>.optionalInt(name: String): Int? {
    val element = get(name) ?: return null
    return if (element === JsonNull) null else element.jsonPrimitive.intOrNull
}

private fun Map<String, JsonElement>.requiredBoolean(name: String): Boolean = requireNotNull(get(name)?.jsonPrimitive?.booleanOrNull)

private fun Map<String, JsonElement>.requiredEnum(
    name: String,
    allowed: Set<String>,
): String = requiredString(name, 100).also { value -> require(value in allowed) }
