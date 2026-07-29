package dev.ndcshelf.app.data.sync

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
import dev.ndcshelf.app.domain.sync.SyncMutation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

fun BookWorkEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "work",
        id,
        mapOf(
            "title" to JsonPrimitive(title),
            "primaryAuthor" to JsonPrimitive(primaryAuthor),
        ),
    )

fun BookEditionEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "edition",
        id,
        mapOf(
            "workId" to JsonPrimitive(workId),
            "isbn13" to isbn13.jsonValue(),
            "publisher" to publisher.jsonValue(),
            "publishedYear" to publishedYear.jsonValue(),
            "coverUrl" to coverUrl.jsonValue(),
            "ndcCode" to ndcCode.jsonValue(),
            "ndcEdition" to ndcEdition.jsonValue(),
            "classificationSource" to JsonPrimitive(classificationSource),
            "bibliographicSource" to JsonPrimitive(bibliographicSource),
        ),
    )

fun OwnedCopyEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "ownedCopy",
        id,
        mapOf(
            "editionId" to JsonPrimitive(editionId),
            "mediaType" to JsonPrimitive(mediaType),
            "location" to JsonPrimitive(location),
            "readingStatus" to JsonPrimitive(readingStatus),
            "addedAt" to JsonPrimitive(addedAt),
            "tierId" to tierId.jsonValue(),
            "shelfOrderKey" to shelfOrderKey.jsonValue(),
            "copyLabel" to JsonPrimitive(copyLabel),
        ),
    )

fun WishlistItemEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "wishlistItem",
        editionId,
        mapOf(
            "status" to JsonPrimitive(status),
            "createdAt" to JsonPrimitive(createdAt),
            "updatedAt" to JsonPrimitive(updatedAt),
        ),
    )

fun LocationRoomEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "locationRoom",
        id,
        mapOf("name" to JsonPrimitive(name), "sortOrder" to JsonPrimitive(sortOrder)),
    )

fun LocationShelfEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "locationShelf",
        id,
        mapOf(
            "roomId" to JsonPrimitive(roomId),
            "name" to JsonPrimitive(name),
            "sortOrder" to JsonPrimitive(sortOrder),
        ),
    )

fun LocationTierEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "locationTier",
        id,
        mapOf(
            "shelfId" to JsonPrimitive(shelfId),
            "name" to JsonPrimitive(name),
            "sortOrder" to JsonPrimitive(sortOrder),
        ),
    )

fun SeriesEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "series",
        id,
        mapOf(
            "name" to JsonPrimitive(name),
            "createdAt" to JsonPrimitive(createdAt),
            "updatedAt" to JsonPrimitive(updatedAt),
        ),
    )

fun SeriesMembershipEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "seriesMembership",
        id,
        mapOf(
            "seriesId" to JsonPrimitive(seriesId),
            "workId" to JsonPrimitive(workId),
            "sortOrderKey" to JsonPrimitive(sortOrderKey),
            "volumeLabel" to JsonPrimitive(volumeLabel),
            "type" to JsonPrimitive(type),
            "createdAt" to JsonPrimitive(createdAt),
            "updatedAt" to JsonPrimitive(updatedAt),
            "origin" to JsonPrimitive(origin),
            "confirmedBy" to JsonPrimitive(confirmedBy),
            "sourceTitle" to JsonPrimitive(sourceTitle),
        ),
    )

fun WorkGroupEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "workGroup",
        id,
        mapOf(
            "title" to JsonPrimitive(title),
            "primaryAuthor" to JsonPrimitive(primaryAuthor),
            "seriesSubstitutionEnabled" to JsonPrimitive(seriesSubstitutionEnabled),
            "createdAt" to JsonPrimitive(createdAt),
            "updatedAt" to JsonPrimitive(updatedAt),
        ),
    )

fun WorkGroupMembershipEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "workGroupMembership",
        id,
        mapOf(
            "groupId" to JsonPrimitive(groupId),
            "workId" to JsonPrimitive(workId),
            "createdAt" to JsonPrimitive(createdAt),
        ),
    )

fun ReadingSessionEntity.toSyncUpsert() =
    SyncMutation.Upsert(
        "readingSession",
        id,
        mapOf(
            "copyId" to JsonPrimitive(copyId),
            "status" to JsonPrimitive(status),
            "startedDay" to startedDay.jsonValue(),
            "finishedDay" to finishedDay.jsonValue(),
            "rating" to rating.jsonValue(),
            "note" to note.jsonValue(),
            "createdAt" to JsonPrimitive(createdAt),
            "updatedAt" to JsonPrimitive(updatedAt),
        ),
    )

fun syncDelete(
    entityType: String,
    entityId: String,
) = SyncMutation.Delete(entityType, entityId)

private fun String?.jsonValue() = this?.let(::JsonPrimitive) ?: JsonNull

private fun Int?.jsonValue() = this?.let(::JsonPrimitive) ?: JsonNull
