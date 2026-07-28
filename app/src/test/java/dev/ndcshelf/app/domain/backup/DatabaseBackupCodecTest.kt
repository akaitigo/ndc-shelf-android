package dev.ndcshelf.app.domain.backup

import dev.ndcshelf.app.data.local.BookEditionEntity
import dev.ndcshelf.app.data.local.BookWorkEntity
import dev.ndcshelf.app.data.local.OwnedCopyEntity
import dev.ndcshelf.app.data.local.LocationRoomEntity
import dev.ndcshelf.app.data.local.LocationShelfEntity
import dev.ndcshelf.app.data.local.LocationTierEntity
import dev.ndcshelf.app.data.local.ScanAttemptEntity
import dev.ndcshelf.app.data.local.ScanSessionEntity
import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.SeriesMembershipEntity
import dev.ndcshelf.app.data.local.WishlistItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DatabaseBackupCodecTest {
    private val codec = DatabaseBackupCodec(currentDatabaseVersion = 1)

    @Test
    fun `current backup round trips every table and metadata`() {
        val snapshot = sampleSnapshot()

        val (archive, metadata) = codec.encode(snapshot, "0.1.2", 1_700_000_000_000)
        val preview = codec.decode(ByteArrayInputStream(archive))

        assertEquals(DatabaseBackupCodec.CURRENT_FORMAT_VERSION, metadata.formatVersion)
        assertEquals(metadata, preview.metadata)
        assertEquals(snapshot, preview.snapshot)
    }

    @Test
    fun `current backup preserves multiple manual editions without ISBN`() {
        val base = sampleSnapshot()
        val snapshot = base.copy(
            works = base.works + listOf(
                BookWorkEntity("manual-work-1", "手動本A", "著者不明"),
                BookWorkEntity("manual-work-2", "手動本B", "著者不明"),
            ),
            editions = base.editions + listOf(
                BookEditionEntity(
                    "manual-edition-1", "manual-work-1", null, null, null, null,
                    null, null, "UNKNOWN", "MANUAL",
                ),
                BookEditionEntity(
                    "manual-edition-2", "manual-work-2", null, null, null, null,
                    null, null, "UNKNOWN", "MANUAL",
                ),
            ),
            copies = base.copies + listOf(
                OwnedCopyEntity(
                    "manual-copy-1", "manual-edition-1", "PHYSICAL", "未設定", "UNREAD", 2,
                ),
                OwnedCopyEntity(
                    "manual-copy-2", "manual-edition-2", "DIGITAL", "未設定", "UNREAD", 3,
                ),
            ),
        )

        val preview = codec.decode(codec.encode(snapshot, "0.3.0", 4).first.inputStream())

        assertEquals(snapshot, preview.snapshot)
    }

    @Test
    fun `changed payload is rejected by checksum`() {
        val (archive, _) = codec.encode(sampleSnapshot(), "0.1.2", 1)
        val entries = unzip(archive).toMutableMap()
        entries["database.json"] = requireNotNull(entries["database.json"]) + ' '.code.toByte()

        val error = assertThrows(BackupCodecException::class.java) {
            codec.decode(ByteArrayInputStream(zip(entries)))
        }

        assertEquals(DatabaseBackupFailure.CHECKSUM_MISMATCH, error.failure)
    }

    @Test
    fun `backup from newer database is rejected before payload use`() {
        val (archive, _) = codec.encode(sampleSnapshot(), "0.1.2", 1)
        val entries = unzip(archive).toMutableMap()
        val manifest = requireNotNull(entries["manifest.json"])
            .decodeToString()
            .replace("\"databaseVersion\":1", "\"databaseVersion\":2")
        entries["manifest.json"] = manifest.encodeToByteArray()

        val error = assertThrows(BackupCodecException::class.java) {
            codec.decode(ByteArrayInputStream(zip(entries)))
        }

        assertEquals(DatabaseBackupFailure.NEWER_DATABASE, error.failure)
    }

    @Test
    fun `manifest format and payload schema versions must match`() {
        val (archive, _) = codec.encode(
            sampleSnapshot().copy(
                wishlistItems = emptyList(),
                scanSessions = emptyList(),
                scanAttempts = emptyList(),
            ),
            "0.1.2",
            1,
        )
        val entries = unzip(archive).toMutableMap()
        val originalPayload = requireNotNull(entries["database.json"])
        val changedPayload = originalPayload.decodeToString()
            .replace(
                "\"schemaVersion\":${DatabaseBackupCodec.CURRENT_FORMAT_VERSION}",
                "\"schemaVersion\":${DatabaseBackupCodec.CURRENT_FORMAT_VERSION - 1}",
            )
            .encodeToByteArray()
        entries["database.json"] = changedPayload
        entries["manifest.json"] = requireNotNull(entries["manifest.json"])
            .decodeToString()
            .replace(originalPayload.sha256(), changedPayload.sha256())
            .encodeToByteArray()

        val error = assertThrows(BackupCodecException::class.java) {
            codec.decode(ByteArrayInputStream(zip(entries)))
        }

        assertEquals(DatabaseBackupFailure.INTEGRITY_FAILED, error.failure)
    }

    @Test
    fun `format seven backup keeps scan history and migrates bibliographic source`() {
        val (archive, _) = codec.encode(sampleSnapshot(), "0.2.0", 1)
        val entries = unzip(archive).toMutableMap()
        val originalPayload = requireNotNull(entries["database.json"])
        val oldPayload = originalPayload.decodeToString()
            .replace("\"schemaVersion\":9", "\"schemaVersion\":7")
            .replace(Regex(",\"bibliographicSource\":\"[^\"]+\""), "")
            .encodeToByteArray()
        entries["database.json"] = oldPayload
        entries["manifest.json"] = requireNotNull(entries["manifest.json"])
            .decodeToString()
            .replace("\"formatVersion\":9", "\"formatVersion\":7")
            .replace(originalPayload.sha256(), oldPayload.sha256())
            .encodeToByteArray()

        val preview = codec.decode(ByteArrayInputStream(zip(entries)))

        assertEquals("NDL", preview.snapshot.editions.single().bibliographicSource)
        assertEquals(sampleSnapshot().scanSessions, preview.snapshot.scanSessions)
        assertEquals(sampleSnapshot().scanAttempts, preview.snapshot.scanAttempts)
    }

    @Test
    fun `format eight backup keeps scan history and bibliographic source without series`() {
        val snapshot = sampleSnapshot().copy(series = emptyList(), seriesMemberships = emptyList())
        val (archive, _) = codec.encode(snapshot, "0.3.0", 1)
        val entries = unzip(archive).toMutableMap()
        val originalPayload = requireNotNull(entries["database.json"])
        val oldPayload = originalPayload.decodeToString()
            .replace("\"schemaVersion\":9", "\"schemaVersion\":8")
            .replace(",\"series\":[]", "")
            .replace(",\"seriesMemberships\":[]", "")
            .encodeToByteArray()
        entries["database.json"] = oldPayload
        entries["manifest.json"] = requireNotNull(entries["manifest.json"])
            .decodeToString()
            .replace("\"formatVersion\":9", "\"formatVersion\":8")
            .replace(",\"seriesCount\":0,\"seriesMembershipCount\":0", "")
            .replace(originalPayload.sha256(), oldPayload.sha256())
            .encodeToByteArray()

        val preview = codec.decode(ByteArrayInputStream(zip(entries)))

        assertEquals("NDL", preview.snapshot.editions.single().bibliographicSource)
        assertEquals(snapshot.scanSessions, preview.snapshot.scanSessions)
        assertEquals(snapshot.scanAttempts, preview.snapshot.scanAttempts)
        assertEquals(emptyList<SeriesEntity>(), preview.snapshot.series)
        assertEquals(emptyList<SeriesMembershipEntity>(), preview.snapshot.seriesMemberships)
    }

    @Test
    fun `format one payload migrates fields added in format two`() {
        val payload = """
            {
              "works":[{"id":"work-1","title":"本","primaryAuthor":"著者"}],
              "editions":[{
                "id":"edition-1","workId":"work-1","isbn13":"9784101010014",
                "publisher":null,"publishedYear":null,"coverUrl":null,
                "ndcCode":null,"ndcEdition":null
              }],
              "copies":[{
                "id":"copy-1","editionId":"edition-1","location":"未設定","addedAt":1
              }]
            }
        """.trimIndent().encodeToByteArray()
        val manifest = """
            {
              "format":"ndc-shelf-room-backup","formatVersion":1,"databaseVersion":0,
              "createdAt":1,"appVersion":"0.1.0","payloadSha256":"${payload.sha256()}",
              "workCount":1,"editionCount":1,"copyCount":1
            }
        """.trimIndent().encodeToByteArray()

        val preview = codec.decode(
            ByteArrayInputStream(zip(mapOf("manifest.json" to manifest, "database.json" to payload))),
        )

        assertEquals("UNKNOWN", preview.snapshot.editions.single().classificationSource)
        assertEquals("PHYSICAL", preview.snapshot.copies.single().mediaType)
        assertEquals("UNREAD", preview.snapshot.copies.single().readingStatus)
        assertEquals("所蔵本", preview.snapshot.copies.single().copyLabel)
    }

    @Test
    fun `format seven backup is restored with no inferred series`() {
        val payload = """
            {
              "schemaVersion":7,
              "works":[{"id":"work-1","title":"第一巻","primaryAuthor":"著者"}],
              "editions":[],"copies":[],"rooms":[],"shelves":[],"tiers":[],
              "wishlistItems":[],"scanSessions":[],"scanAttempts":[]
            }
        """.trimIndent().encodeToByteArray()
        val manifest = """
            {
              "format":"ndc-shelf-room-backup","formatVersion":7,"databaseVersion":7,
              "createdAt":1,"appVersion":"0.3.0","payloadSha256":"${payload.sha256()}",
              "workCount":1,"editionCount":0,"copyCount":0,"wishlistCount":0,
              "scanSessionCount":0,"scanAttemptCount":0
            }
        """.trimIndent().encodeToByteArray()

        val preview = DatabaseBackupCodec(currentDatabaseVersion = 8).decode(
            ByteArrayInputStream(zip(mapOf("manifest.json" to manifest, "database.json" to payload))),
        )

        assertEquals(listOf("work-1"), preview.snapshot.works.map { it.id })
        assertEquals(emptyList<SeriesEntity>(), preview.snapshot.series)
        assertEquals(emptyList<SeriesMembershipEntity>(), preview.snapshot.seriesMemberships)
    }

    @Test
    fun `snapshot with missing foreign key is rejected`() {
        val invalid = sampleSnapshot().copy(works = emptyList())

        val error = assertThrows(BackupCodecException::class.java) {
            codec.encode(invalid, "0.1.2", 1)
        }

        assertEquals(DatabaseBackupFailure.INTEGRITY_FAILED, error.failure)
    }

    @Test
    fun `invalid copy label is rejected before backup or restore`() {
        val invalid = sampleSnapshot().copy(
            copies = sampleSnapshot().copies.map { it.copy(copyLabel = " ") },
        )

        val error = assertThrows(BackupCodecException::class.java) {
            codec.encode(invalid, "0.1.2", 1)
        }

        assertEquals(DatabaseBackupFailure.INTEGRITY_FAILED, error.failure)
    }

    @Test
    fun `invalid series enum and order key are rejected before backup`() {
        val sample = sampleSnapshot()
        val invalid = sample.copy(
            seriesMemberships = sample.seriesMemberships.map {
                it.copy(type = "UNKNOWN", sortOrderKey = "not-hex")
            },
        )

        val error = assertThrows(BackupCodecException::class.java) {
            codec.encode(invalid, "0.4.0", 1)
        }

        assertEquals(DatabaseBackupFailure.INTEGRITY_FAILED, error.failure)
    }

    @Test
    fun `series membership with missing series is rejected before restore`() {
        val sample = sampleSnapshot()
        val invalid = sample.copy(series = emptyList())

        val error = assertThrows(BackupCodecException::class.java) {
            codec.encode(invalid, "0.4.0", 1)
        }

        assertEquals(DatabaseBackupFailure.INTEGRITY_FAILED, error.failure)
    }

    @Test
    fun `aggregate text limit is checked before archive allocation`() {
        val constrained = DatabaseBackupCodec(
            currentDatabaseVersion = 1,
            limits = DatabaseBackupLimits(maxTotalCharacters = 10),
        )

        val error = assertThrows(BackupCodecException::class.java) {
            constrained.encode(sampleSnapshot(), "0.1.2", 1)
        }

        assertEquals(DatabaseBackupFailure.TOO_LARGE, error.failure)
    }

    private fun sampleSnapshot() = DatabaseSnapshot(
        works = listOf(BookWorkEntity("work-1", "吾輩は猫である", "夏目漱石")),
        editions = listOf(
            BookEditionEntity(
                id = "edition-1",
                workId = "work-1",
                isbn13 = "9784101010014",
                publisher = "新潮社",
                publishedYear = 2003,
                coverUrl = "https://ndlsearch.ndl.go.jp/cover.jpg",
                ndcCode = "913.6",
                ndcEdition = "10",
                classificationSource = "NDL",
            ),
        ),
        copies = listOf(
            OwnedCopyEntity(
                id = "copy-1",
                editionId = "edition-1",
                mediaType = "PHYSICAL",
                location = "書斎",
                readingStatus = "READING",
                addedAt = 1_700_000_000_000,
                tierId = "tier-1",
                shelfOrderKey = "7f0011223344556677",
                copyLabel = "保存用",
            ),
        ),
        rooms = listOf(LocationRoomEntity("room-1", "書斎", 0)),
        shelves = listOf(LocationShelfEntity("shelf-1", "room-1", "本棚", 0)),
        tiers = listOf(LocationTierEntity("tier-1", "shelf-1", "上段", 0)),
        wishlistItems = listOf(
            WishlistItemEntity(
                editionId = "edition-1",
                status = "RESERVED",
                createdAt = 1_600_000_000_000,
                updatedAt = 1_700_000_000_000,
            ),
        ),
        scanSessions = listOf(
            ScanSessionEntity(
                id = "session-1",
                startedAt = 1_600_000_000_000,
                endedAt = 1_700_000_000_000,
            ),
        ),
        scanAttempts = listOf(
            ScanAttemptEntity(
                id = "attempt-1",
                sessionId = "session-1",
                isbn = "9784101010014",
                outcome = "ADDED",
                copyId = "copy-1",
                copySnapshot = "b".repeat(64),
                attemptedAt = 1_650_000_000_000,
                undoneAt = null,
            ),
        ),
        series = listOf(SeriesEntity("series-1", "吾輩シリーズ", 1, 2)),
        seriesMemberships = listOf(
            SeriesMembershipEntity(
                id = "membership-1",
                seriesId = "series-1",
                workId = "work-1",
                sortOrderKey = "80",
                volumeLabel = "上巻",
                type = "MAIN_STORY",
                createdAt = 1,
                updatedAt = 2,
            ),
        ),
    )

    private fun unzip(archive: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
}
