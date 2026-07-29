package dev.ndcshelf.app.domain.backup

import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.ReadingSessionEntity
import dev.ndcshelf.app.data.local.SavedSearchEntity
import dev.ndcshelf.app.data.local.ScanAttemptEntity
import dev.ndcshelf.app.data.local.ScanSessionEntity
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.SeriesMembershipEntity
import dev.ndcshelf.app.data.local.SeriesReleaseCandidateEntity
import dev.ndcshelf.app.data.local.SeriesWatchEntity
import dev.ndcshelf.app.data.local.TagAssignmentEntity
import dev.ndcshelf.app.data.local.TagEntity
import dev.ndcshelf.app.data.local.WishlistItemEntity
import dev.ndcshelf.app.data.local.WorkGroupEntity
import dev.ndcshelf.app.data.local.WorkGroupMembershipEntity
import dev.ndcshelf.app.scanner.Isbn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class DatabaseBackupCodec(
    private val currentDatabaseVersion: Int,
    private val limits: DatabaseBackupLimits = DatabaseBackupLimits(),
) {
    fun encode(
        snapshot: DatabaseSnapshot,
        appVersion: String,
        createdAt: Long,
    ): Pair<ByteArray, DatabaseBackupMetadata> {
        validateSnapshot(snapshot)
        val payload = encodePayload(snapshot).toString().encodeToByteArray()
        val metadata =
            DatabaseBackupMetadata(
                formatVersion = CURRENT_FORMAT_VERSION,
                databaseVersion = currentDatabaseVersion,
                createdAt = createdAt,
                appVersion = appVersion,
                workCount = snapshot.works.size,
                editionCount = snapshot.editions.size,
                copyCount = snapshot.copies.size,
                wishlistCount = snapshot.wishlistItems.size,
                scanSessionCount = snapshot.scanSessions.size,
                scanAttemptCount = snapshot.scanAttempts.size,
                seriesCount = snapshot.series.size,
                seriesMembershipCount = snapshot.seriesMemberships.size,
                workGroupCount = snapshot.workGroups.size,
                workGroupMembershipCount = snapshot.workGroupMemberships.size,
                seriesWatchCount = snapshot.seriesWatches.size,
                seriesReleaseCandidateCount = snapshot.seriesReleaseCandidates.size,
                readingSessionCount = snapshot.readingSessions.size,
                tagCount = snapshot.tags.size,
                tagAssignmentCount = snapshot.tagAssignments.size,
                savedSearchCount = snapshot.savedSearches.size,
            )
        val manifest = encodeManifest(metadata, payload.sha256()).toString().encodeToByteArray()
        val archive =
            ByteArrayOutputStream().use { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY))
                    zip.write(payload)
                    zip.closeEntry()
                }
                bytes.toByteArray()
            }
        if (archive.size > limits.maxArchiveBytes) {
            throw BackupCodecException(DatabaseBackupFailure.TOO_LARGE, "Backup exceeds size limit")
        }
        return archive to metadata
    }

    fun decode(input: InputStream): DatabaseBackupPreview {
        val entries = readEntries(input)
        val manifestBytes = entries[MANIFEST_ENTRY] ?: invalid("Missing manifest")
        val payloadBytes = entries[PAYLOAD_ENTRY] ?: invalid("Missing payload")
        val manifest = parseObject(manifestBytes)
        val format = manifest.requiredString("format")
        if (format != FORMAT_ID) unsupported("Unknown backup format")
        val formatVersion = manifest.requiredInt("formatVersion")
        if (formatVersion !in SUPPORTED_FORMAT_VERSIONS) unsupported("Unsupported format version")
        val databaseVersion = manifest.requiredInt("databaseVersion")
        if (databaseVersion > currentDatabaseVersion) {
            throw BackupCodecException(DatabaseBackupFailure.NEWER_DATABASE, "Newer database")
        }
        val expectedChecksum = manifest.requiredString("payloadSha256")
        if (!expectedChecksum.equals(payloadBytes.sha256(), ignoreCase = true)) {
            throw BackupCodecException(DatabaseBackupFailure.CHECKSUM_MISMATCH, "Checksum mismatch")
        }

        val metadata =
            DatabaseBackupMetadata(
                formatVersion = formatVersion,
                databaseVersion = databaseVersion,
                createdAt = manifest.requiredLong("createdAt"),
                appVersion = manifest.requiredString("appVersion"),
                workCount = manifest.requiredInt("workCount"),
                editionCount = manifest.requiredInt("editionCount"),
                copyCount = manifest.requiredInt("copyCount"),
                wishlistCount = if (formatVersion < 6) 0 else manifest.requiredInt("wishlistCount"),
                scanSessionCount = if (formatVersion < 7) 0 else manifest.requiredInt("scanSessionCount"),
                scanAttemptCount = if (formatVersion < 7) 0 else manifest.requiredInt("scanAttemptCount"),
                seriesCount = if (formatVersion < 9) 0 else manifest.requiredInt("seriesCount"),
                seriesMembershipCount =
                    if (formatVersion < 9) {
                        0
                    } else {
                        manifest.requiredInt("seriesMembershipCount")
                    },
                workGroupCount = if (formatVersion < 11) 0 else manifest.requiredInt("workGroupCount"),
                workGroupMembershipCount =
                    if (formatVersion < 11) {
                        0
                    } else {
                        manifest.requiredInt("workGroupMembershipCount")
                    },
                seriesWatchCount = if (formatVersion < 12) 0 else manifest.requiredInt("seriesWatchCount"),
                seriesReleaseCandidateCount =
                    if (formatVersion < 12) {
                        0
                    } else {
                        manifest.requiredInt("seriesReleaseCandidateCount")
                    },
                readingSessionCount =
                    if (formatVersion < 13) {
                        0
                    } else {
                        manifest.requiredInt("readingSessionCount")
                    },
                tagCount = if (formatVersion < 14) 0 else manifest.requiredInt("tagCount"),
                tagAssignmentCount =
                    if (formatVersion < 14) {
                        0
                    } else {
                        manifest.requiredInt("tagAssignmentCount")
                    },
                savedSearchCount =
                    if (formatVersion < 14) {
                        0
                    } else {
                        manifest.requiredInt("savedSearchCount")
                    },
            )
        val snapshot = decodePayload(parseObject(payloadBytes), formatVersion)
        if (metadata.workCount != snapshot.works.size ||
            metadata.editionCount != snapshot.editions.size ||
            metadata.copyCount != snapshot.copies.size ||
            metadata.wishlistCount != snapshot.wishlistItems.size ||
            metadata.scanSessionCount != snapshot.scanSessions.size ||
            metadata.scanAttemptCount != snapshot.scanAttempts.size ||
            metadata.seriesCount != snapshot.series.size ||
            metadata.seriesMembershipCount != snapshot.seriesMemberships.size ||
            metadata.workGroupCount != snapshot.workGroups.size ||
            metadata.workGroupMembershipCount != snapshot.workGroupMemberships.size ||
            metadata.seriesWatchCount != snapshot.seriesWatches.size ||
            metadata.seriesReleaseCandidateCount != snapshot.seriesReleaseCandidates.size ||
            metadata.readingSessionCount != snapshot.readingSessions.size ||
            metadata.tagCount != snapshot.tags.size ||
            metadata.tagAssignmentCount != snapshot.tagAssignments.size ||
            metadata.savedSearchCount != snapshot.savedSearches.size
        ) {
            invalid("Manifest counts do not match payload")
        }
        validateSnapshot(snapshot)
        return DatabaseBackupPreview(metadata, snapshot)
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val counting = LimitedInputStream(input, limits.maxArchiveBytes)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(counting).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name !in ALLOWED_ENTRIES || entries.containsKey(entry.name)) {
                    invalid("Unexpected or duplicate ZIP entry")
                }
                val maxBytes =
                    if (entry.name == MANIFEST_ENTRY) {
                        limits.maxManifestBytes
                    } else {
                        limits.maxPayloadBytes
                    }
                entries[entry.name] = zip.readLimited(maxBytes)
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun encodeManifest(
        metadata: DatabaseBackupMetadata,
        checksum: String,
    ) = buildJsonObject {
        put("format", JsonPrimitive(FORMAT_ID))
        put("formatVersion", JsonPrimitive(metadata.formatVersion))
        put("databaseVersion", JsonPrimitive(metadata.databaseVersion))
        put("createdAt", JsonPrimitive(metadata.createdAt))
        put("appVersion", JsonPrimitive(metadata.appVersion))
        put("payloadSha256", JsonPrimitive(checksum))
        put("workCount", JsonPrimitive(metadata.workCount))
        put("editionCount", JsonPrimitive(metadata.editionCount))
        put("copyCount", JsonPrimitive(metadata.copyCount))
        put("wishlistCount", JsonPrimitive(metadata.wishlistCount))
        put("scanSessionCount", JsonPrimitive(metadata.scanSessionCount))
        put("scanAttemptCount", JsonPrimitive(metadata.scanAttemptCount))
        put("seriesCount", JsonPrimitive(metadata.seriesCount))
        put("seriesMembershipCount", JsonPrimitive(metadata.seriesMembershipCount))
        put("workGroupCount", JsonPrimitive(metadata.workGroupCount))
        put("workGroupMembershipCount", JsonPrimitive(metadata.workGroupMembershipCount))
        put("seriesWatchCount", JsonPrimitive(metadata.seriesWatchCount))
        put("seriesReleaseCandidateCount", JsonPrimitive(metadata.seriesReleaseCandidateCount))
        put("readingSessionCount", JsonPrimitive(metadata.readingSessionCount))
        put("tagCount", JsonPrimitive(metadata.tagCount))
        put("tagAssignmentCount", JsonPrimitive(metadata.tagAssignmentCount))
        put("savedSearchCount", JsonPrimitive(metadata.savedSearchCount))
    }

    private fun encodePayload(snapshot: DatabaseSnapshot) =
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(CURRENT_PAYLOAD_SCHEMA_VERSION))
            put(
                "works",
                buildJsonArray {
                    snapshot.works.forEach { work ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(work.id))
                                put("title", JsonPrimitive(work.title))
                                put("primaryAuthor", JsonPrimitive(work.primaryAuthor))
                            },
                        )
                    }
                },
            )
            put(
                "editions",
                buildJsonArray {
                    snapshot.editions.forEach { edition ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(edition.id))
                                put("workId", JsonPrimitive(edition.workId))
                                putNullable("isbn13", edition.isbn13)
                                putNullable("publisher", edition.publisher)
                                putNullable("publishedYear", edition.publishedYear)
                                putNullable("coverUrl", edition.coverUrl)
                                putNullable("ndcCode", edition.ndcCode)
                                putNullable("ndcEdition", edition.ndcEdition)
                                put("classificationSource", JsonPrimitive(edition.classificationSource))
                                put("bibliographicSource", JsonPrimitive(edition.bibliographicSource))
                            },
                        )
                    }
                },
            )
            put(
                "copies",
                buildJsonArray {
                    snapshot.copies.forEach { copy ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(copy.id))
                                put("editionId", JsonPrimitive(copy.editionId))
                                put("mediaType", JsonPrimitive(copy.mediaType))
                                put("location", JsonPrimitive(copy.location))
                                put("readingStatus", JsonPrimitive(copy.readingStatus))
                                put("addedAt", JsonPrimitive(copy.addedAt))
                                putNullable("tierId", copy.tierId)
                                putNullable("shelfOrderKey", copy.shelfOrderKey)
                                put("copyLabel", JsonPrimitive(copy.copyLabel))
                            },
                        )
                    }
                },
            )
            put(
                "rooms",
                buildJsonArray {
                    snapshot.rooms.forEach { room ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(room.id))
                                put("name", JsonPrimitive(room.name))
                                put("sortOrder", JsonPrimitive(room.sortOrder))
                            },
                        )
                    }
                },
            )
            put(
                "shelves",
                buildJsonArray {
                    snapshot.shelves.forEach { shelf ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(shelf.id))
                                put("roomId", JsonPrimitive(shelf.roomId))
                                put("name", JsonPrimitive(shelf.name))
                                put("sortOrder", JsonPrimitive(shelf.sortOrder))
                            },
                        )
                    }
                },
            )
            put(
                "tiers",
                buildJsonArray {
                    snapshot.tiers.forEach { tier ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(tier.id))
                                put("shelfId", JsonPrimitive(tier.shelfId))
                                put("name", JsonPrimitive(tier.name))
                                put("sortOrder", JsonPrimitive(tier.sortOrder))
                            },
                        )
                    }
                },
            )
            put(
                "wishlistItems",
                buildJsonArray {
                    snapshot.wishlistItems.forEach { item ->
                        add(
                            buildJsonObject {
                                put("editionId", JsonPrimitive(item.editionId))
                                put("status", JsonPrimitive(item.status))
                                put("createdAt", JsonPrimitive(item.createdAt))
                                put("updatedAt", JsonPrimitive(item.updatedAt))
                            },
                        )
                    }
                },
            )
            put(
                "scanSessions",
                buildJsonArray {
                    snapshot.scanSessions.forEach { session ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(session.id))
                                put("startedAt", JsonPrimitive(session.startedAt))
                                putNullable("endedAt", session.endedAt)
                            },
                        )
                    }
                },
            )
            put(
                "scanAttempts",
                buildJsonArray {
                    snapshot.scanAttempts.forEach { attempt ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(attempt.id))
                                put("sessionId", JsonPrimitive(attempt.sessionId))
                                put("isbn", JsonPrimitive(attempt.isbn))
                                put("outcome", JsonPrimitive(attempt.outcome))
                                putNullable("copyId", attempt.copyId)
                                putNullable("copySnapshot", attempt.copySnapshot)
                                put("attemptedAt", JsonPrimitive(attempt.attemptedAt))
                                putNullable("undoneAt", attempt.undoneAt)
                            },
                        )
                    }
                },
            )
            put(
                "series",
                buildJsonArray {
                    snapshot.series.forEach { series ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(series.id))
                                put("name", JsonPrimitive(series.name))
                                put("createdAt", JsonPrimitive(series.createdAt))
                                put("updatedAt", JsonPrimitive(series.updatedAt))
                            },
                        )
                    }
                },
            )
            put(
                "seriesMemberships",
                buildJsonArray {
                    snapshot.seriesMemberships.forEach { membership ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(membership.id))
                                put("seriesId", JsonPrimitive(membership.seriesId))
                                put("workId", JsonPrimitive(membership.workId))
                                put("sortOrderKey", JsonPrimitive(membership.sortOrderKey))
                                put("volumeLabel", JsonPrimitive(membership.volumeLabel))
                                put("type", JsonPrimitive(membership.type))
                                put("createdAt", JsonPrimitive(membership.createdAt))
                                put("updatedAt", JsonPrimitive(membership.updatedAt))
                                put("origin", JsonPrimitive(membership.origin))
                                put("confirmedBy", JsonPrimitive(membership.confirmedBy))
                                put("sourceTitle", JsonPrimitive(membership.sourceTitle))
                            },
                        )
                    }
                },
            )
            put(
                "workGroups",
                buildJsonArray {
                    snapshot.workGroups.forEach { group ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(group.id))
                                put("title", JsonPrimitive(group.title))
                                put("primaryAuthor", JsonPrimitive(group.primaryAuthor))
                                put("seriesSubstitutionEnabled", JsonPrimitive(group.seriesSubstitutionEnabled))
                                put("createdAt", JsonPrimitive(group.createdAt))
                                put("updatedAt", JsonPrimitive(group.updatedAt))
                            },
                        )
                    }
                },
            )
            put(
                "workGroupMemberships",
                buildJsonArray {
                    snapshot.workGroupMemberships.forEach { membership ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(membership.id))
                                put("groupId", JsonPrimitive(membership.groupId))
                                put("workId", JsonPrimitive(membership.workId))
                                put("createdAt", JsonPrimitive(membership.createdAt))
                            },
                        )
                    }
                },
            )
            put(
                "scanSessions",
                buildJsonArray {
                    snapshot.scanSessions.forEach { session ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(session.id))
                                put("startedAt", JsonPrimitive(session.startedAt))
                                putNullable("endedAt", session.endedAt)
                            },
                        )
                    }
                },
            )
            put(
                "scanAttempts",
                buildJsonArray {
                    snapshot.scanAttempts.forEach { attempt ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(attempt.id))
                                put("sessionId", JsonPrimitive(attempt.sessionId))
                                put("isbn", JsonPrimitive(attempt.isbn))
                                put("outcome", JsonPrimitive(attempt.outcome))
                                putNullable("copyId", attempt.copyId)
                                putNullable("copySnapshot", attempt.copySnapshot)
                                put("attemptedAt", JsonPrimitive(attempt.attemptedAt))
                                putNullable("undoneAt", attempt.undoneAt)
                            },
                        )
                    }
                },
            )
            put(
                "seriesWatches",
                buildJsonArray {
                    snapshot.seriesWatches.forEach { watch ->
                        add(
                            buildJsonObject {
                                put("seriesId", JsonPrimitive(watch.seriesId))
                                put("queryTitle", JsonPrimitive(watch.queryTitle))
                                put("enabled", JsonPrimitive(watch.enabled))
                                put("createdAt", JsonPrimitive(watch.createdAt))
                                put("updatedAt", JsonPrimitive(watch.updatedAt))
                                putNullable("lastCheckedAt", watch.lastCheckedAt)
                                putNullable("lastSuccessfulAt", watch.lastSuccessfulAt)
                            },
                        )
                    }
                },
            )
            put(
                "seriesReleaseCandidates",
                buildJsonArray {
                    snapshot.seriesReleaseCandidates.forEach { candidate ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(candidate.id))
                                put("seriesId", JsonPrimitive(candidate.seriesId))
                                put("sourceRecordId", JsonPrimitive(candidate.sourceRecordId))
                                put("title", JsonPrimitive(candidate.title))
                                put("primaryAuthor", JsonPrimitive(candidate.primaryAuthor))
                                putNullable("isbn13", candidate.isbn13)
                                putNullable("publisher", candidate.publisher)
                                putNullable("publishedDate", candidate.publishedDate)
                                put("firstSeenAt", JsonPrimitive(candidate.firstSeenAt))
                                put("lastSeenAt", JsonPrimitive(candidate.lastSeenAt))
                                putNullable("notifiedAt", candidate.notifiedAt)
                            },
                        )
                    }
                },
            )
            put(
                "readingSessions",
                buildJsonArray {
                    snapshot.readingSessions.forEach { session ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(session.id))
                                put("copyId", JsonPrimitive(session.copyId))
                                put("status", JsonPrimitive(session.status))
                                putNullable("startedDay", session.startedDay)
                                putNullable("finishedDay", session.finishedDay)
                                putNullable("rating", session.rating)
                                putNullable("note", session.note)
                                put("createdAt", JsonPrimitive(session.createdAt))
                                put("updatedAt", JsonPrimitive(session.updatedAt))
                            },
                        )
                    }
                },
            )
            put(
                "tags",
                buildJsonArray {
                    snapshot.tags.forEach { tag ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(tag.id))
                                put("name", JsonPrimitive(tag.name))
                                put("colorRole", JsonPrimitive(tag.colorRole))
                                put("createdAt", JsonPrimitive(tag.createdAt))
                                put("updatedAt", JsonPrimitive(tag.updatedAt))
                            },
                        )
                    }
                },
            )
            put(
                "tagAssignments",
                buildJsonArray {
                    snapshot.tagAssignments.forEach { assignment ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(assignment.id))
                                put("tagId", JsonPrimitive(assignment.tagId))
                                put("workId", JsonPrimitive(assignment.workId))
                                put("createdAt", JsonPrimitive(assignment.createdAt))
                            },
                        )
                    }
                },
            )
            put(
                "savedSearches",
                buildJsonArray {
                    snapshot.savedSearches.forEach { savedSearch ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(savedSearch.id))
                                put("name", JsonPrimitive(savedSearch.name))
                                put("criteriaJson", JsonPrimitive(savedSearch.criteriaJson))
                                put("createdAt", JsonPrimitive(savedSearch.createdAt))
                                put("updatedAt", JsonPrimitive(savedSearch.updatedAt))
                            },
                        )
                    }
                },
            )
        }

    private fun decodePayload(
        payload: JsonObject,
        formatVersion: Int,
    ): DatabaseSnapshot {
        val schemaVersion = payload["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        if (schemaVersion > CURRENT_PAYLOAD_SCHEMA_VERSION) unsupported("Newer payload schema")
        if (schemaVersion != formatVersion) invalid("Format and payload schema versions differ")
        val works =
            payload.requiredArray("works").map { element ->
                val value = element.jsonObject
                BookWorkEntity(
                    id = value.requiredString("id"),
                    title = value.requiredString("title"),
                    primaryAuthor = value.requiredString("primaryAuthor"),
                )
            }
        val editions =
            payload.requiredArray("editions").map { element ->
                val value = element.jsonObject
                BookEditionEntity(
                    id = value.requiredString("id"),
                    workId = value.requiredString("workId"),
                    isbn13 =
                        if (schemaVersion < 8) {
                            value.requiredString("isbn13")
                        } else {
                            value.optionalString("isbn13")
                        },
                    publisher = value.optionalString("publisher"),
                    publishedYear = value.optionalInt("publishedYear"),
                    coverUrl = value.optionalString("coverUrl"),
                    ndcCode = value.optionalString("ndcCode"),
                    ndcEdition = value.optionalString("ndcEdition"),
                    classificationSource =
                        value.optionalString("classificationSource")
                            ?: if (formatVersion == 1) "UNKNOWN" else invalid("Missing classificationSource"),
                    bibliographicSource =
                        if (schemaVersion < 8) {
                            "NDL"
                        } else {
                            value.requiredString("bibliographicSource")
                        },
                )
            }
        val copies =
            payload.requiredArray("copies").map { element ->
                val value = element.jsonObject
                OwnedCopyEntity(
                    id = value.requiredString("id"),
                    editionId = value.requiredString("editionId"),
                    mediaType =
                        value.optionalString("mediaType")
                            ?: if (formatVersion == 1) "PHYSICAL" else invalid("Missing mediaType"),
                    location = value.requiredString("location"),
                    readingStatus =
                        value.optionalString("readingStatus")
                            ?: if (formatVersion == 1) "UNREAD" else invalid("Missing readingStatus"),
                    addedAt = value.requiredLong("addedAt"),
                    tierId = value.optionalString("tierId"),
                    shelfOrderKey = value.optionalString("shelfOrderKey"),
                    copyLabel =
                        if (schemaVersion < 5) {
                            "所蔵本"
                        } else {
                            value.requiredString("copyLabel")
                        },
                )
            }
        val rooms =
            payload.versionedArray("rooms", schemaVersion, 3).map { element ->
                val value = element.jsonObject
                LocationRoomEntity(
                    id = value.requiredString("id"),
                    name = value.requiredString("name"),
                    sortOrder = value.requiredInt("sortOrder"),
                )
            }
        val shelves =
            payload.versionedArray("shelves", schemaVersion, 3).map { element ->
                val value = element.jsonObject
                LocationShelfEntity(
                    id = value.requiredString("id"),
                    roomId = value.requiredString("roomId"),
                    name = value.requiredString("name"),
                    sortOrder = value.requiredInt("sortOrder"),
                )
            }
        val tiers =
            payload.versionedArray("tiers", schemaVersion, 3).map { element ->
                val value = element.jsonObject
                LocationTierEntity(
                    id = value.requiredString("id"),
                    shelfId = value.requiredString("shelfId"),
                    name = value.requiredString("name"),
                    sortOrder = value.requiredInt("sortOrder"),
                )
            }
        val wishlistItems =
            payload.versionedArray("wishlistItems", schemaVersion, 6).map { element ->
                val value = element.jsonObject
                WishlistItemEntity(
                    editionId = value.requiredString("editionId"),
                    status = value.requiredString("status"),
                    createdAt = value.requiredLong("createdAt"),
                    updatedAt = value.requiredLong("updatedAt"),
                )
            }
        val scanSessions =
            payload.versionedArray("scanSessions", schemaVersion, 7).map { element ->
                val value = element.jsonObject
                ScanSessionEntity(
                    id = value.requiredString("id"),
                    startedAt = value.requiredLong("startedAt"),
                    endedAt = value.optionalLong("endedAt"),
                )
            }
        val scanAttempts =
            payload.versionedArray("scanAttempts", schemaVersion, 7).map { element ->
                val value = element.jsonObject
                ScanAttemptEntity(
                    id = value.requiredString("id"),
                    sessionId = value.requiredString("sessionId"),
                    isbn = value.requiredString("isbn"),
                    outcome = value.requiredString("outcome"),
                    copyId = value.optionalString("copyId"),
                    copySnapshot = value.optionalString("copySnapshot"),
                    attemptedAt = value.requiredLong("attemptedAt"),
                    undoneAt = value.optionalLong("undoneAt"),
                )
            }
        val series =
            payload.versionedArray("series", schemaVersion, 9).map { element ->
                val value = element.jsonObject
                SeriesEntity(
                    id = value.requiredString("id"),
                    name = value.requiredString("name"),
                    createdAt = value.requiredLong("createdAt"),
                    updatedAt = value.requiredLong("updatedAt"),
                )
            }
        val seriesMemberships =
            payload
                .versionedArray(
                    "seriesMemberships",
                    schemaVersion,
                    9,
                ).map { element ->
                    val value = element.jsonObject
                    SeriesMembershipEntity(
                        id = value.requiredString("id"),
                        seriesId = value.requiredString("seriesId"),
                        workId = value.requiredString("workId"),
                        sortOrderKey = value.requiredString("sortOrderKey"),
                        volumeLabel = value.requiredString("volumeLabel"),
                        type = value.requiredString("type"),
                        createdAt = value.requiredLong("createdAt"),
                        updatedAt = value.requiredLong("updatedAt"),
                        origin = if (schemaVersion < 10) "MANUAL" else value.requiredString("origin"),
                        confirmedBy = if (schemaVersion < 10) "USER" else value.requiredString("confirmedBy"),
                        sourceTitle = if (schemaVersion < 10) "" else value.requiredString("sourceTitle"),
                    )
                }
        val workGroups =
            payload.versionedArray("workGroups", schemaVersion, 11).map { element ->
                val value = element.jsonObject
                WorkGroupEntity(
                    id = value.requiredString("id"),
                    title = value.requiredString("title"),
                    primaryAuthor = value.requiredString("primaryAuthor"),
                    seriesSubstitutionEnabled = value.requiredBoolean("seriesSubstitutionEnabled"),
                    createdAt = value.requiredLong("createdAt"),
                    updatedAt = value.requiredLong("updatedAt"),
                )
            }
        val workGroupMemberships =
            payload
                .versionedArray(
                    "workGroupMemberships",
                    schemaVersion,
                    11,
                ).map { element ->
                    val value = element.jsonObject
                    WorkGroupMembershipEntity(
                        id = value.requiredString("id"),
                        groupId = value.requiredString("groupId"),
                        workId = value.requiredString("workId"),
                        createdAt = value.requiredLong("createdAt"),
                    )
                }
        val seriesWatches =
            payload.versionedArray("seriesWatches", schemaVersion, 12).map { element ->
                val value = element.jsonObject
                SeriesWatchEntity(
                    seriesId = value.requiredString("seriesId"),
                    queryTitle = value.requiredString("queryTitle"),
                    enabled = value.requiredBoolean("enabled"),
                    createdAt = value.requiredLong("createdAt"),
                    updatedAt = value.requiredLong("updatedAt"),
                    lastCheckedAt = value.optionalLong("lastCheckedAt"),
                    lastSuccessfulAt = value.optionalLong("lastSuccessfulAt"),
                )
            }
        val seriesReleaseCandidates =
            payload
                .versionedArray(
                    "seriesReleaseCandidates",
                    schemaVersion,
                    12,
                ).map { element ->
                    val value = element.jsonObject
                    SeriesReleaseCandidateEntity(
                        id = value.requiredString("id"),
                        seriesId = value.requiredString("seriesId"),
                        sourceRecordId = value.requiredString("sourceRecordId"),
                        title = value.requiredString("title"),
                        primaryAuthor = value.requiredString("primaryAuthor"),
                        isbn13 = value.optionalString("isbn13"),
                        publisher = value.optionalString("publisher"),
                        publishedDate = value.optionalString("publishedDate"),
                        firstSeenAt = value.requiredLong("firstSeenAt"),
                        lastSeenAt = value.requiredLong("lastSeenAt"),
                        notifiedAt = value.optionalLong("notifiedAt"),
                    )
                }
        val readingSessions =
            payload.versionedArray("readingSessions", schemaVersion, 13).map { element ->
                val value = element.jsonObject
                ReadingSessionEntity(
                    id = value.requiredString("id"),
                    copyId = value.requiredString("copyId"),
                    status = value.requiredString("status"),
                    startedDay = value.optionalString("startedDay"),
                    finishedDay = value.optionalString("finishedDay"),
                    rating = value.optionalInt("rating"),
                    note = value.optionalString("note"),
                    createdAt = value.requiredLong("createdAt"),
                    updatedAt = value.requiredLong("updatedAt"),
                )
            }
        val tags =
            payload.versionedArray("tags", schemaVersion, 14).map { element ->
                val value = element.jsonObject
                TagEntity(
                    id = value.requiredString("id"),
                    name = value.requiredString("name"),
                    colorRole = value.requiredString("colorRole"),
                    createdAt = value.requiredLong("createdAt"),
                    updatedAt = value.requiredLong("updatedAt"),
                )
            }
        val tagAssignments =
            payload.versionedArray("tagAssignments", schemaVersion, 14).map { element ->
                val value = element.jsonObject
                TagAssignmentEntity(
                    id = value.requiredString("id"),
                    tagId = value.requiredString("tagId"),
                    workId = value.requiredString("workId"),
                    createdAt = value.requiredLong("createdAt"),
                )
            }
        val savedSearches =
            payload.versionedArray("savedSearches", schemaVersion, 14).map { element ->
                val value = element.jsonObject
                SavedSearchEntity(
                    id = value.requiredString("id"),
                    name = value.requiredString("name"),
                    criteriaJson = value.requiredString("criteriaJson"),
                    createdAt = value.requiredLong("createdAt"),
                    updatedAt = value.requiredLong("updatedAt"),
                )
            }
        return DatabaseSnapshot(
            works = works,
            editions = editions,
            copies = copies,
            rooms = rooms,
            shelves = shelves,
            tiers = tiers,
            wishlistItems = wishlistItems,
            series = series,
            seriesMemberships = seriesMemberships,
            scanSessions = scanSessions,
            scanAttempts = scanAttempts,
            workGroups = workGroups,
            workGroupMemberships = workGroupMemberships,
            seriesWatches = seriesWatches,
            seriesReleaseCandidates = seriesReleaseCandidates,
            readingSessions = readingSessions,
            tags = tags,
            tagAssignments = tagAssignments,
            savedSearches = savedSearches,
        )
    }

    private fun validateSnapshot(snapshot: DatabaseSnapshot) {
        if (snapshot.works.size > limits.maxRecords ||
            snapshot.editions.size > limits.maxRecords ||
            snapshot.copies.size > limits.maxRecords ||
            snapshot.rooms.size > limits.maxRecords ||
            snapshot.shelves.size > limits.maxRecords ||
            snapshot.tiers.size > limits.maxRecords ||
            snapshot.wishlistItems.size > limits.maxRecords ||
            snapshot.scanSessions.size > limits.maxRecords ||
            snapshot.scanAttempts.size > limits.maxRecords ||
            snapshot.series.size > limits.maxRecords ||
            snapshot.seriesMemberships.size > limits.maxRecords ||
            snapshot.workGroups.size > limits.maxRecords ||
            snapshot.workGroupMemberships.size > limits.maxRecords ||
            snapshot.seriesWatches.size > limits.maxRecords ||
            snapshot.seriesReleaseCandidates.size > limits.maxRecords ||
            snapshot.readingSessions.size > limits.maxRecords ||
            snapshot.tags.size > limits.maxRecords ||
            snapshot.tagAssignments.size > limits.maxRecords ||
            snapshot.savedSearches.size > limits.maxRecords
        ) {
            tooLarge("Too many records")
        }
        snapshot.works.ensureUnique(BookWorkEntity::id)
        snapshot.editions.ensureUnique(BookEditionEntity::id)
        snapshot.editions.mapNotNull(BookEditionEntity::isbn13).ensureUnique { it }
        snapshot.copies.ensureUnique(OwnedCopyEntity::id)
        snapshot.rooms.ensureUnique(LocationRoomEntity::id)
        snapshot.rooms.ensureUnique(LocationRoomEntity::name)
        snapshot.shelves.ensureUnique(LocationShelfEntity::id)
        snapshot.shelves.ensureUnique { it.roomId to it.name }
        snapshot.tiers.ensureUnique(LocationTierEntity::id)
        snapshot.wishlistItems.ensureUnique(WishlistItemEntity::editionId)
        snapshot.scanSessions.ensureUnique(ScanSessionEntity::id)
        snapshot.scanAttempts.ensureUnique(ScanAttemptEntity::id)
        snapshot.series.ensureUnique(SeriesEntity::id)
        snapshot.seriesMemberships.ensureUnique(SeriesMembershipEntity::id)
        snapshot.seriesMemberships.ensureUnique { it.seriesId to it.workId }
        snapshot.seriesMemberships.ensureUnique { it.seriesId to it.sortOrderKey }
        snapshot.workGroups.ensureUnique(WorkGroupEntity::id)
        snapshot.workGroupMemberships.ensureUnique(WorkGroupMembershipEntity::id)
        snapshot.workGroupMemberships.ensureUnique(WorkGroupMembershipEntity::workId)
        snapshot.workGroupMemberships.ensureUnique { it.groupId to it.workId }
        snapshot.seriesWatches.ensureUnique(SeriesWatchEntity::seriesId)
        snapshot.seriesReleaseCandidates.ensureUnique(SeriesReleaseCandidateEntity::id)
        snapshot.seriesReleaseCandidates.ensureUnique { it.seriesId to it.sourceRecordId }
        snapshot.readingSessions.ensureUnique(ReadingSessionEntity::id)
        snapshot.tags.ensureUnique(TagEntity::id)
        snapshot.tags.ensureUnique(TagEntity::name)
        snapshot.tagAssignments.ensureUnique(TagAssignmentEntity::id)
        snapshot.tagAssignments.ensureUnique { it.tagId to it.workId }
        snapshot.savedSearches.ensureUnique(SavedSearchEntity::id)
        snapshot.savedSearches.ensureUnique(SavedSearchEntity::name)
        snapshot.tiers.ensureUnique { it.shelfId to it.name }
        val workIds = snapshot.works.mapTo(hashSetOf(), BookWorkEntity::id)
        val editionIds = snapshot.editions.mapTo(hashSetOf(), BookEditionEntity::id)
        val roomIds = snapshot.rooms.mapTo(hashSetOf(), LocationRoomEntity::id)
        val shelfIds = snapshot.shelves.mapTo(hashSetOf(), LocationShelfEntity::id)
        val tierIds = snapshot.tiers.mapTo(hashSetOf(), LocationTierEntity::id)
        val seriesIds = snapshot.series.mapTo(hashSetOf(), SeriesEntity::id)
        val seriesWatchIds = snapshot.seriesWatches.mapTo(hashSetOf(), SeriesWatchEntity::seriesId)
        val scanSessionIds = snapshot.scanSessions.mapTo(hashSetOf(), ScanSessionEntity::id)
        val workGroupIds = snapshot.workGroups.mapTo(hashSetOf(), WorkGroupEntity::id)
        val copyIds = snapshot.copies.mapTo(hashSetOf(), OwnedCopyEntity::id)
        val tagIds = snapshot.tags.mapTo(hashSetOf(), TagEntity::id)
        if (snapshot.editions.any { it.workId !in workIds } ||
            snapshot.copies.any {
                it.editionId !in editionIds || it.tierId != null && it.tierId !in tierIds
            } ||
            snapshot.shelves.any { it.roomId !in roomIds } ||
            snapshot.tiers.any { it.shelfId !in shelfIds } ||
            snapshot.wishlistItems.any { it.editionId !in editionIds } ||
            snapshot.scanAttempts.any { it.sessionId !in scanSessionIds } ||
            snapshot.seriesMemberships.any {
                it.seriesId !in seriesIds || it.workId !in workIds
            } ||
            snapshot.workGroupMemberships.any {
                it.groupId !in workGroupIds || it.workId !in workIds
            } || snapshot.seriesWatches.any { it.seriesId !in seriesIds } ||
            snapshot.seriesReleaseCandidates.any {
                it.seriesId !in seriesIds || it.seriesId !in seriesWatchIds
            } ||
            snapshot.readingSessions.any { session -> session.copyId !in copyIds } ||
            snapshot.tagAssignments.any { assignment ->
                assignment.tagId !in tagIds || assignment.workId !in workIds
            }
        ) {
            invalid("Foreign key reference is missing")
        }
        if (snapshot.tags.any { tag ->
                tag.id.isBlank() ||
                    tag.name.isBlank() ||
                    tag.name.length > MAX_TAG_NAME_LENGTH ||
                    tag.name != tag.name.trim() ||
                    tag.name.any { it.isISOControl() } ||
                    tag.colorRole !in TAG_COLOR_ROLES ||
                    tag.createdAt < 0 ||
                    tag.updatedAt < tag.createdAt
            } ||
            snapshot.tagAssignments.any { assignment ->
                assignment.id.isBlank() || assignment.createdAt < 0
            } ||
            snapshot.savedSearches.any { savedSearch ->
                savedSearch.id.isBlank() ||
                    savedSearch.name.isBlank() ||
                    savedSearch.name.length > MAX_TAG_NAME_LENGTH ||
                    savedSearch.name.any { it.isISOControl() } ||
                    savedSearch.criteriaJson.isBlank() ||
                    savedSearch.criteriaJson.length > MAX_CRITERIA_JSON_LENGTH ||
                    savedSearch.createdAt < 0 ||
                    savedSearch.updatedAt < savedSearch.createdAt
            }
        ) {
            invalid("Invalid tag or saved search")
        }
        if (snapshot.readingSessions.any { session ->
                session.id.isBlank() ||
                    session.status !in READING_SESSION_STATUSES ||
                    session.startedDay?.matches(PARTIAL_DATE) == false ||
                    session.finishedDay?.matches(PARTIAL_DATE) == false ||
                    session.rating?.let { it !in 1..5 } == true ||
                    session.note?.let { note ->
                        note.isBlank() || note.length > MAX_READING_NOTE_LENGTH || '\u0000' in note
                    } == true ||
                    session.createdAt < 0 ||
                    session.updatedAt < session.createdAt
            }
        ) {
            invalid("Invalid reading session")
        }
        if (snapshot.copies.any { copy ->
                copy.tierId == null && copy.shelfOrderKey != null ||
                    copy.shelfOrderKey?.let { key ->
                        key.length % 2 != 0 || key.any { it !in '0'..'9' && it !in 'a'..'f' }
                    } == true
            }
        ) {
            invalid("Invalid shelf order key")
        }
        if (snapshot.copies.any { copy ->
                copy.copyLabel.isBlank() ||
                    copy.copyLabel.length > MAX_COPY_LABEL_LENGTH ||
                    '\u0000' in copy.copyLabel
            }
        ) {
            invalid("Invalid copy label")
        }
        if (snapshot.wishlistItems.any { item ->
                item.status !in WISHLIST_STATUSES ||
                    item.createdAt < 0 ||
                    item.updatedAt < item.createdAt
            }
        ) {
            invalid("Invalid wishlist item")
        }
        if (snapshot.scanSessions.count { it.endedAt == null } > 1 ||
            snapshot.scanSessions.any { it.startedAt < 0 || it.endedAt?.let { end -> end < it.startedAt } == true }
        ) {
            invalid("Invalid scan session")
        }
        val sessionsById = snapshot.scanSessions.associateBy(ScanSessionEntity::id)
        if (snapshot.scanAttempts.any { attempt ->
                val session = sessionsById.getValue(attempt.sessionId)
                val added = attempt.outcome == "ADDED"
                attempt.outcome !in SCAN_ATTEMPT_OUTCOMES ||
                    attempt.isbn.length > MAX_RECORDED_ISBN_LENGTH ||
                    attempt.attemptedAt < session.startedAt ||
                    session.endedAt?.let { attempt.attemptedAt > it } == true ||
                    attempt.undoneAt?.let { it < attempt.attemptedAt } == true ||
                    added != (attempt.copyId != null && attempt.copySnapshot?.matches(SHA256_REGEX) == true) ||
                    !added && attempt.undoneAt != null
            }
        ) {
            invalid("Invalid scan attempt")
        }
        if (snapshot.series.any { item ->
                item.name.isBlank() || item.createdAt < 0 || item.updatedAt < item.createdAt
            } ||
            snapshot.seriesMemberships.any { item ->
                item.volumeLabel.isBlank() ||
                    item.type !in SERIES_MEMBERSHIP_TYPES ||
                    item.origin !in SERIES_MEMBERSHIP_ORIGINS ||
                    item.confirmedBy != "USER" ||
                    item.createdAt < 0 ||
                    item.updatedAt < item.createdAt ||
                    !item.sortOrderKey.isValidOrderKey()
            }
        ) {
            invalid("Invalid series data")
        }
        if (snapshot.seriesWatches.any { watch ->
                watch.queryTitle.isBlank() || watch.queryTitle.length > 200 ||
                    watch.createdAt < 0 || watch.updatedAt < watch.createdAt ||
                    watch.lastCheckedAt?.let { it < watch.createdAt } == true ||
                    watch.lastSuccessfulAt?.let { it < watch.createdAt } == true ||
                    watch.lastSuccessfulAt != null &&
                    (watch.lastCheckedAt == null || watch.lastSuccessfulAt > watch.lastCheckedAt)
            } ||
            snapshot.seriesReleaseCandidates.any { candidate ->
                candidate.sourceRecordId.isBlank() || candidate.title.isBlank() ||
                    candidate.primaryAuthor.isBlank() || candidate.firstSeenAt < 0 ||
                    candidate.lastSeenAt < candidate.firstSeenAt ||
                    candidate.notifiedAt?.let { it < candidate.firstSeenAt } == true ||
                    candidate.isbn13?.let(Isbn::normalizeToIsbn13) != candidate.isbn13 ||
                    candidate.publishedDate?.matches(SERIES_RELEASE_DATE) == false
            }
        ) {
            invalid("Invalid series watch data")
        }
        val workGroupMembershipCounts =
            snapshot.workGroupMemberships
                .groupingBy(WorkGroupMembershipEntity::groupId)
                .eachCount()
        if (snapshot.workGroups.any { group ->
                group.id.isBlank() || group.title.isBlank() || group.createdAt < 0 ||
                    group.updatedAt < group.createdAt ||
                    (workGroupMembershipCounts[group.id] ?: 0) < MIN_WORK_GROUP_SIZE
            } ||
            snapshot.workGroupMemberships.any { membership ->
                membership.id.isBlank() || membership.createdAt < 0
            }
        ) {
            invalid("Invalid work group data")
        }
        val editionById = snapshot.editions.associateBy(BookEditionEntity::id)
        if (snapshot.editions.any {
                it.bibliographicSource !in BIBLIOGRAPHIC_SOURCES ||
                    it.isbn13 == null && it.bibliographicSource != "MANUAL"
            } || snapshot.wishlistItems.any { editionById[it.editionId]?.isbn13 == null }
        ) {
            invalid("Invalid bibliographic source or ISBN")
        }
        val strings =
            buildList {
                snapshot.works.forEach {
                    add(it.id)
                    add(it.title)
                    add(it.primaryAuthor)
                }
                snapshot.editions.forEach {
                    add(it.id)
                    add(it.workId)
                    add(it.isbn13.orEmpty())
                    add(it.publisher.orEmpty())
                    add(it.coverUrl.orEmpty())
                    add(it.ndcCode.orEmpty())
                    add(it.ndcEdition.orEmpty())
                    add(it.classificationSource)
                    add(it.bibliographicSource)
                }
                snapshot.copies.forEach {
                    add(it.id)
                    add(it.editionId)
                    add(it.mediaType)
                    add(it.location)
                    add(it.readingStatus)
                    add(it.tierId.orEmpty())
                    add(it.shelfOrderKey.orEmpty())
                    add(it.copyLabel)
                }
                snapshot.rooms.forEach {
                    add(it.id)
                    add(it.name)
                }
                snapshot.shelves.forEach {
                    add(it.id)
                    add(it.roomId)
                    add(it.name)
                }
                snapshot.tiers.forEach {
                    add(it.id)
                    add(it.shelfId)
                    add(it.name)
                }
                snapshot.wishlistItems.forEach {
                    add(it.editionId)
                    add(it.status)
                }
                snapshot.scanSessions.forEach { add(it.id) }
                snapshot.scanAttempts.forEach {
                    add(it.id)
                    add(it.sessionId)
                    add(it.isbn)
                    add(it.outcome)
                    add(it.copyId.orEmpty())
                    add(it.copySnapshot.orEmpty())
                }
                snapshot.series.forEach {
                    add(it.id)
                    add(it.name)
                }
                snapshot.seriesMemberships.forEach {
                    add(it.id)
                    add(it.seriesId)
                    add(it.workId)
                    add(it.sortOrderKey)
                    add(it.volumeLabel)
                    add(it.type)
                    add(it.origin)
                    add(it.confirmedBy)
                    add(it.sourceTitle)
                }
                snapshot.workGroups.forEach {
                    add(it.id)
                    add(it.title)
                    add(it.primaryAuthor)
                }
                snapshot.readingSessions.forEach {
                    add(it.id)
                    add(it.copyId)
                    add(it.status)
                    add(it.startedDay.orEmpty())
                    add(it.finishedDay.orEmpty())
                    add(it.note.orEmpty())
                }
                snapshot.tags.forEach {
                    add(it.id)
                    add(it.name)
                    add(it.colorRole)
                }
                snapshot.tagAssignments.forEach {
                    add(it.id)
                    add(it.tagId)
                    add(it.workId)
                }
                snapshot.savedSearches.forEach {
                    add(it.id)
                    add(it.name)
                    add(it.criteriaJson)
                }
                snapshot.workGroupMemberships.forEach {
                    add(it.id)
                    add(it.groupId)
                    add(it.workId)
                }
                snapshot.seriesWatches.forEach {
                    add(it.seriesId)
                    add(it.queryTitle)
                }
                snapshot.seriesReleaseCandidates.forEach {
                    add(it.id)
                    add(it.seriesId)
                    add(it.sourceRecordId)
                    add(it.title)
                    add(it.primaryAuthor)
                    add(it.isbn13.orEmpty())
                    add(it.publisher.orEmpty())
                    add(it.publishedDate.orEmpty())
                }
            }
        if (strings.any { it.length > limits.maxStringLength }) invalid("String is too long")
        if (strings.sumOf { it.length.toLong() } > limits.maxTotalCharacters) {
            tooLarge("Snapshot text is too large")
        }
    }

    private fun <T, K> List<T>.ensureUnique(selector: (T) -> K) {
        if (map(selector).distinct().size != size) invalid("Duplicate identifier")
    }

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.let { runCatching { it.jsonArray }.getOrNull() } ?: invalid("Missing $name")

    private fun JsonObject.versionedArray(
        name: String,
        schemaVersion: Int,
        addedIn: Int,
    ): JsonArray = if (schemaVersion < addedIn) JsonArray(emptyList()) else requiredArray(name)

    private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: invalid("Missing $name")

    private fun JsonObject.requiredInt(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: invalid("Invalid $name")

    private fun JsonObject.requiredLong(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: invalid("Invalid $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val primitive = this[name]?.jsonPrimitive ?: invalid("Invalid $name")
        if (primitive.isString) invalid("Invalid $name")
        return primitive.booleanOrNull ?: invalid("Invalid $name")
    }

    private fun JsonObject.optionalString(name: String): String? =
        when (val value = this[name]) {
            null, JsonNull -> null
            else -> value.jsonPrimitive.contentOrNull ?: invalid("Invalid $name")
        }

    private fun JsonObject.optionalInt(name: String): Int? =
        when (val value = this[name]) {
            null, JsonNull -> null
            else -> value.jsonPrimitive.intOrNull ?: invalid("Invalid $name")
        }

    private fun JsonObject.optionalLong(name: String): Long? =
        when (val value = this[name]) {
            null, JsonNull -> null
            else -> value.jsonPrimitive.longOrNull ?: invalid("Invalid $name")
        }

    private fun parseObject(bytes: ByteArray): JsonObject =
        try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (_: Exception) {
            invalid("Invalid JSON")
        }

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw BackupCodecException(DatabaseBackupFailure.TOO_LARGE, "Entry too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        private val maxBytes: Int,
    ) : InputStream() {
        private var readBytes = 0

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val value = delegate.read(buffer, offset, length)
            if (value > 0) count(value)
            return value
        }

        override fun close() = delegate.close()

        private fun count(value: Int) {
            readBytes += value
            if (readBytes > maxBytes) {
                throw BackupCodecException(DatabaseBackupFailure.TOO_LARGE, "Archive too large")
            }
        }
    }

    private fun invalid(message: String): Nothing = throw BackupCodecException(DatabaseBackupFailure.INTEGRITY_FAILED, message)

    private fun unsupported(message: String): Nothing = throw BackupCodecException(DatabaseBackupFailure.UNSUPPORTED_FORMAT, message)

    private fun tooLarge(message: String): Nothing = throw BackupCodecException(DatabaseBackupFailure.TOO_LARGE, message)

    companion object {
        const val CURRENT_FORMAT_VERSION = 14
        private const val CURRENT_PAYLOAD_SCHEMA_VERSION = 14
        private const val MAX_COPY_LABEL_LENGTH = 100
        private const val MAX_READING_NOTE_LENGTH = 2_000
        private const val MAX_RECORDED_ISBN_LENGTH = 32
        private const val MIN_WORK_GROUP_SIZE = 2
        private const val FORMAT_ID = "ndc-shelf-room-backup"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val PAYLOAD_ENTRY = "database.json"
        private val ALLOWED_ENTRIES = setOf(MANIFEST_ENTRY, PAYLOAD_ENTRY)
        private val SUPPORTED_FORMAT_VERSIONS = 1..CURRENT_FORMAT_VERSION
        private val WISHLIST_STATUSES = setOf("WANTED", "RESERVED")
        private val SCAN_ATTEMPT_OUTCOMES = setOf("ADDED", "DUPLICATE", "INVALID", "NOT_FOUND", "FAILURE")
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private val BIBLIOGRAPHIC_SOURCES = setOf("NDL", "MANUAL")
        private val SERIES_MEMBERSHIP_TYPES =
            setOf(
                "MAIN_STORY",
                "SIDE_STORY",
                "OMNIBUS",
                "OTHER",
            )
        private val SERIES_MEMBERSHIP_ORIGINS = setOf("TITLE_SUGGESTION", "MANUAL")
        private val SERIES_RELEASE_DATE = Regex("\\d{4}(?:-\\d{2}(?:-\\d{2})?)?")
        private val READING_SESSION_STATUSES = setOf("READING", "PAUSED", "FINISHED")
        private const val MAX_TAG_NAME_LENGTH = 50
        private const val MAX_CRITERIA_JSON_LENGTH = 4_096
        private val TAG_COLOR_ROLES =
            setOf(
                "GRAY",
                "RED",
                "ORANGE",
                "YELLOW",
                "GREEN",
                "TEAL",
                "BLUE",
                "PURPLE",
                "PINK",
                "BROWN",
            )
        private val PARTIAL_DATE = Regex("\\d{4}(?:-\\d{2}(?:-\\d{2})?)?")
    }
}

private fun String.isValidOrderKey(): Boolean = isNotEmpty() && length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }

internal data class DatabaseBackupLimits(
    val maxArchiveBytes: Int = 25 * 1024 * 1024,
    val maxManifestBytes: Int = 64 * 1024,
    val maxPayloadBytes: Int = 50 * 1024 * 1024,
    val maxRecords: Int = 10_000,
    val maxStringLength: Int = 16_384,
    val maxTotalCharacters: Long = 20_000_000,
)

internal class BackupCodecException(
    val failure: DatabaseBackupFailure,
    message: String,
) : IllegalArgumentException(message)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: String?,
) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: Int?,
) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: Long?,
) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}
