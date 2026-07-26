package dev.ndcshelf.app.domain.backup

import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val metadata = DatabaseBackupMetadata(
            formatVersion = CURRENT_FORMAT_VERSION,
            databaseVersion = currentDatabaseVersion,
            createdAt = createdAt,
            appVersion = appVersion,
            workCount = snapshot.works.size,
            editionCount = snapshot.editions.size,
            copyCount = snapshot.copies.size,
        )
        val manifest = encodeManifest(metadata, payload.sha256()).toString().encodeToByteArray()
        val archive = ByteArrayOutputStream().use { bytes ->
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

        val metadata = DatabaseBackupMetadata(
            formatVersion = formatVersion,
            databaseVersion = databaseVersion,
            createdAt = manifest.requiredLong("createdAt"),
            appVersion = manifest.requiredString("appVersion"),
            workCount = manifest.requiredInt("workCount"),
            editionCount = manifest.requiredInt("editionCount"),
            copyCount = manifest.requiredInt("copyCount"),
        )
        val snapshot = decodePayload(parseObject(payloadBytes), formatVersion)
        if (metadata.workCount != snapshot.works.size ||
            metadata.editionCount != snapshot.editions.size ||
            metadata.copyCount != snapshot.copies.size
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
                val maxBytes = if (entry.name == MANIFEST_ENTRY) {
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

    private fun encodeManifest(metadata: DatabaseBackupMetadata, checksum: String) = buildJsonObject {
        put("format", JsonPrimitive(FORMAT_ID))
        put("formatVersion", JsonPrimitive(metadata.formatVersion))
        put("databaseVersion", JsonPrimitive(metadata.databaseVersion))
        put("createdAt", JsonPrimitive(metadata.createdAt))
        put("appVersion", JsonPrimitive(metadata.appVersion))
        put("payloadSha256", JsonPrimitive(checksum))
        put("workCount", JsonPrimitive(metadata.workCount))
        put("editionCount", JsonPrimitive(metadata.editionCount))
        put("copyCount", JsonPrimitive(metadata.copyCount))
    }

    private fun encodePayload(snapshot: DatabaseSnapshot) = buildJsonObject {
        put("schemaVersion", JsonPrimitive(CURRENT_PAYLOAD_SCHEMA_VERSION))
        put("works", buildJsonArray {
            snapshot.works.forEach { work ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(work.id))
                    put("title", JsonPrimitive(work.title))
                    put("primaryAuthor", JsonPrimitive(work.primaryAuthor))
                })
            }
        })
        put("editions", buildJsonArray {
            snapshot.editions.forEach { edition ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(edition.id))
                    put("workId", JsonPrimitive(edition.workId))
                    put("isbn13", JsonPrimitive(edition.isbn13))
                    putNullable("publisher", edition.publisher)
                    putNullable("publishedYear", edition.publishedYear)
                    putNullable("coverUrl", edition.coverUrl)
                    putNullable("ndcCode", edition.ndcCode)
                    putNullable("ndcEdition", edition.ndcEdition)
                    put("classificationSource", JsonPrimitive(edition.classificationSource))
                })
            }
        })
        put("copies", buildJsonArray {
            snapshot.copies.forEach { copy ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(copy.id))
                    put("editionId", JsonPrimitive(copy.editionId))
                    put("mediaType", JsonPrimitive(copy.mediaType))
                    put("location", JsonPrimitive(copy.location))
                    put("readingStatus", JsonPrimitive(copy.readingStatus))
                    put("addedAt", JsonPrimitive(copy.addedAt))
                    putNullable("tierId", copy.tierId)
                    putNullable("shelfOrderKey", copy.shelfOrderKey)
                    put("copyLabel", JsonPrimitive(copy.copyLabel))
                })
            }
        })
        put("rooms", buildJsonArray {
            snapshot.rooms.forEach { room ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(room.id))
                    put("name", JsonPrimitive(room.name))
                    put("sortOrder", JsonPrimitive(room.sortOrder))
                })
            }
        })
        put("shelves", buildJsonArray {
            snapshot.shelves.forEach { shelf ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(shelf.id))
                    put("roomId", JsonPrimitive(shelf.roomId))
                    put("name", JsonPrimitive(shelf.name))
                    put("sortOrder", JsonPrimitive(shelf.sortOrder))
                })
            }
        })
        put("tiers", buildJsonArray {
            snapshot.tiers.forEach { tier ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(tier.id))
                    put("shelfId", JsonPrimitive(tier.shelfId))
                    put("name", JsonPrimitive(tier.name))
                    put("sortOrder", JsonPrimitive(tier.sortOrder))
                })
            }
        })
    }

    private fun decodePayload(payload: JsonObject, formatVersion: Int): DatabaseSnapshot {
        val schemaVersion = payload["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        if (schemaVersion > CURRENT_PAYLOAD_SCHEMA_VERSION) unsupported("Newer payload schema")
        val works = payload.requiredArray("works").map { element ->
            val value = element.jsonObject
            BookWorkEntity(
                id = value.requiredString("id"),
                title = value.requiredString("title"),
                primaryAuthor = value.requiredString("primaryAuthor"),
            )
        }
        val editions = payload.requiredArray("editions").map { element ->
            val value = element.jsonObject
            BookEditionEntity(
                id = value.requiredString("id"),
                workId = value.requiredString("workId"),
                isbn13 = value.requiredString("isbn13"),
                publisher = value.optionalString("publisher"),
                publishedYear = value.optionalInt("publishedYear"),
                coverUrl = value.optionalString("coverUrl"),
                ndcCode = value.optionalString("ndcCode"),
                ndcEdition = value.optionalString("ndcEdition"),
                classificationSource = value.optionalString("classificationSource")
                    ?: if (formatVersion == 1) "UNKNOWN" else invalid("Missing classificationSource"),
            )
        }
        val copies = payload.requiredArray("copies").map { element ->
            val value = element.jsonObject
            OwnedCopyEntity(
                id = value.requiredString("id"),
                editionId = value.requiredString("editionId"),
                mediaType = value.optionalString("mediaType")
                    ?: if (formatVersion == 1) "PHYSICAL" else invalid("Missing mediaType"),
                location = value.requiredString("location"),
                readingStatus = value.optionalString("readingStatus")
                    ?: if (formatVersion == 1) "UNREAD" else invalid("Missing readingStatus"),
                addedAt = value.requiredLong("addedAt"),
                tierId = value.optionalString("tierId"),
                shelfOrderKey = value.optionalString("shelfOrderKey"),
                copyLabel = if (schemaVersion < 5) {
                    "所蔵本"
                } else {
                    value.requiredString("copyLabel")
                },
            )
        }
        val rooms = payload.versionedArray("rooms", schemaVersion, 3).map { element ->
            val value = element.jsonObject
            LocationRoomEntity(
                id = value.requiredString("id"),
                name = value.requiredString("name"),
                sortOrder = value.requiredInt("sortOrder"),
            )
        }
        val shelves = payload.versionedArray("shelves", schemaVersion, 3).map { element ->
            val value = element.jsonObject
            LocationShelfEntity(
                id = value.requiredString("id"),
                roomId = value.requiredString("roomId"),
                name = value.requiredString("name"),
                sortOrder = value.requiredInt("sortOrder"),
            )
        }
        val tiers = payload.versionedArray("tiers", schemaVersion, 3).map { element ->
            val value = element.jsonObject
            LocationTierEntity(
                id = value.requiredString("id"),
                shelfId = value.requiredString("shelfId"),
                name = value.requiredString("name"),
                sortOrder = value.requiredInt("sortOrder"),
            )
        }
        return DatabaseSnapshot(works, editions, copies, rooms, shelves, tiers)
    }

    private fun validateSnapshot(snapshot: DatabaseSnapshot) {
        if (snapshot.works.size > limits.maxRecords ||
            snapshot.editions.size > limits.maxRecords ||
            snapshot.copies.size > limits.maxRecords ||
            snapshot.rooms.size > limits.maxRecords ||
            snapshot.shelves.size > limits.maxRecords ||
            snapshot.tiers.size > limits.maxRecords
        ) tooLarge("Too many records")
        snapshot.works.ensureUnique(BookWorkEntity::id)
        snapshot.editions.ensureUnique(BookEditionEntity::id)
        snapshot.editions.ensureUnique(BookEditionEntity::isbn13)
        snapshot.copies.ensureUnique(OwnedCopyEntity::id)
        snapshot.rooms.ensureUnique(LocationRoomEntity::id)
        snapshot.rooms.ensureUnique(LocationRoomEntity::name)
        snapshot.shelves.ensureUnique(LocationShelfEntity::id)
        snapshot.shelves.ensureUnique { it.roomId to it.name }
        snapshot.tiers.ensureUnique(LocationTierEntity::id)
        snapshot.tiers.ensureUnique { it.shelfId to it.name }
        val workIds = snapshot.works.mapTo(hashSetOf(), BookWorkEntity::id)
        val editionIds = snapshot.editions.mapTo(hashSetOf(), BookEditionEntity::id)
        val roomIds = snapshot.rooms.mapTo(hashSetOf(), LocationRoomEntity::id)
        val shelfIds = snapshot.shelves.mapTo(hashSetOf(), LocationShelfEntity::id)
        val tierIds = snapshot.tiers.mapTo(hashSetOf(), LocationTierEntity::id)
        if (snapshot.editions.any { it.workId !in workIds } ||
            snapshot.copies.any {
                it.editionId !in editionIds || it.tierId != null && it.tierId !in tierIds
            } ||
            snapshot.shelves.any { it.roomId !in roomIds } ||
            snapshot.tiers.any { it.shelfId !in shelfIds }
        ) invalid("Foreign key reference is missing")
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
        val strings = buildList {
            snapshot.works.forEach { add(it.id); add(it.title); add(it.primaryAuthor) }
            snapshot.editions.forEach {
                add(it.id); add(it.workId); add(it.isbn13); add(it.publisher.orEmpty())
                add(it.coverUrl.orEmpty()); add(it.ndcCode.orEmpty()); add(it.ndcEdition.orEmpty())
                add(it.classificationSource)
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
            snapshot.rooms.forEach { add(it.id); add(it.name) }
            snapshot.shelves.forEach { add(it.id); add(it.roomId); add(it.name) }
            snapshot.tiers.forEach { add(it.id); add(it.shelfId); add(it.name) }
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

    private fun JsonObject.versionedArray(name: String, schemaVersion: Int, addedIn: Int): JsonArray =
        if (schemaVersion < addedIn) JsonArray(emptyList()) else requiredArray(name)

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: invalid("Missing $name")

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: invalid("Invalid $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: invalid("Invalid $name")

    private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        else -> value.jsonPrimitive.contentOrNull ?: invalid("Invalid $name")
    }

    private fun JsonObject.optionalInt(name: String): Int? = when (val value = this[name]) {
        null, JsonNull -> null
        else -> value.jsonPrimitive.intOrNull ?: invalid("Invalid $name")
    }

    private fun parseObject(bytes: ByteArray): JsonObject = try {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (_: Exception) {
        invalid("Invalid JSON")
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
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

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
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

    private fun invalid(message: String): Nothing =
        throw BackupCodecException(DatabaseBackupFailure.INTEGRITY_FAILED, message)

    private fun unsupported(message: String): Nothing =
        throw BackupCodecException(DatabaseBackupFailure.UNSUPPORTED_FORMAT, message)

    private fun tooLarge(message: String): Nothing =
        throw BackupCodecException(DatabaseBackupFailure.TOO_LARGE, message)

    companion object {
        const val CURRENT_FORMAT_VERSION = 5
        private const val CURRENT_PAYLOAD_SCHEMA_VERSION = 5
        private const val MAX_COPY_LABEL_LENGTH = 100
        private const val FORMAT_ID = "ndc-shelf-room-backup"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val PAYLOAD_ENTRY = "database.json"
        private val ALLOWED_ENTRIES = setOf(MANIFEST_ENTRY, PAYLOAD_ENTRY)
        private val SUPPORTED_FORMAT_VERSIONS = 1..CURRENT_FORMAT_VERSION
    }
}

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

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Int?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}
