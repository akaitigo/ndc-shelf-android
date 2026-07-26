package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryImportPlannerTest {
    private val planner = LibraryImportPlanner(nowMillis = { NOW })

    @Test
    fun `valid record is normalized and previewed without changing existing data`() {
        val result = planner.preview(
            batch = batch(
                record(
                    copyId = " copy-1 ",
                    title = "  本の題名  ",
                    isbn13 = "4-8204-1807-6",
                    classificationSource = "ndl",
                    coverUrl = "https://ndlsearch.ndl.go.jp/thumbnail/9784820418078.jpg",
                ),
            ),
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Valid

        assertEquals(1, result.preview.additions.size)
        assertEquals(0, result.preview.updates.size)
        assertEquals(1, result.preview.changeCount)
        assertEquals("copy-1", result.preview.additions.single().copyId)
        assertEquals("本の題名", result.preview.additions.single().title)
        assertEquals("9784820418078", result.preview.additions.single().isbn13)
        assertEquals(
            "https://ndlsearch.ndl.go.jp/thumbnail/9784820418078.jpg",
            result.preview.additions.single().coverUrl,
        )
        assertEquals(ClassificationSource.NDL, result.preview.additions.single().classificationSource)
    }

    @Test
    fun `source size record count and string length limits are enforced`() {
        val smallLimits = LibraryImportLimits(
            maxSourceBytes = 10,
            maxRecords = 1,
            maxTextLength = 4,
        )
        val limitedPlanner = LibraryImportPlanner(smallLimits) { NOW }

        val oversized = limitedPlanner.preview(
            LibraryImportBatch(sourceSizeBytes = 11, records = emptyList()),
            emptyList(),
            ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Invalid
        val tooMany = limitedPlanner.preview(
            LibraryImportBatch(sourceSizeBytes = 10, records = listOf(record(), record(copyId = "copy-2"))),
            emptyList(),
            ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Invalid
        val tooLong = limitedPlanner.preview(
            batch(record(title = "12345")),
            emptyList(),
            ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Invalid

        assertTrue(oversized.errors.any { it.reason.contains("10バイト") })
        assertTrue(tooMany.errors.any { it.reason.contains("1件") })
        assertTrue(tooLong.errors.any { it.field == "title" && it.recordNumber == 1 })
    }

    @Test
    fun `duplicate IDs and inconsistent references are rejected with positions`() {
        val result = planner.preview(
            batch(
                record(copyId = "same-copy", workId = "same-work", title = "本A"),
                record(
                    copyId = "same-copy",
                    workId = "same-work",
                    editionId = "edition-2",
                    isbn13 = "9784101010014",
                    title = "本B",
                ),
            ),
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Invalid

        assertTrue(
            result.errors.any {
                it.recordNumber == 2 && it.field == "copyId" && it.reason.contains("レコード1")
            },
        )
        assertTrue(result.errors.any { it.field == "workId" })
    }

    @Test
    fun `unknown enums invalid timestamps and invalid ISBN are rejected`() {
        val result = planner.preview(
            batch(
                record(
                    isbn13 = "not-isbn",
                    copyId = "invalid/id",
                    mediaType = "PAPER",
                    addedAt = NOW + TWO_DAYS,
                    coverUrl = "file:///data/data/private",
                ),
            ),
            existingBooks = emptyList(),
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Invalid

        assertTrue(result.errors.any { it.field == "isbn13" })
        assertTrue(result.errors.any { it.field == "copyId" })
        assertTrue(result.errors.any { it.field == "coverUrl" })
        assertTrue(result.errors.any { it.field == "mediaType" && it.reason.contains("未知") })
        assertTrue(result.errors.any { it.field == "addedAt" })
    }

    @Test
    fun `validation errors are capped for malicious input`() {
        val records = List(200) { index ->
            record(copyId = "copy-$index").copy(
                title = null,
                primaryAuthor = null,
                isbn13 = null,
                classificationSource = null,
                mediaType = null,
                readingStatus = null,
                addedAt = null,
            )
        }

        val result = planner.preview(
            LibraryImportBatch(sourceSizeBytes = 1, records = records),
            emptyList(),
            ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Invalid

        assertEquals(100, result.errors.size)
    }

    @Test
    fun `conflicts are skipped by default or mapped onto existing IDs for update`() {
        val existing = book(title = "旧題", location = "旧棚")
        val incoming = record(
            copyId = "foreign-copy",
            workId = "foreign-work",
            editionId = "foreign-edition",
            title = "新題",
            location = "新棚",
        )

        val skipped = planner.preview(
            batch(incoming),
            listOf(existing),
            ImportConflictPolicy.SKIP_EXISTING,
        ) as ImportPreviewResult.Valid
        val updated = planner.preview(
            batch(incoming),
            listOf(existing),
            ImportConflictPolicy.UPDATE_EXISTING,
        ) as ImportPreviewResult.Valid

        assertEquals(1, skipped.preview.skippedCount)
        assertEquals(0, skipped.preview.changeCount)
        val update = updated.preview.updates.single()
        assertEquals(existing.copyId, update.copyId)
        assertEquals(existing.workId, update.workId)
        assertEquals(existing.editionId, update.editionId)
        assertEquals("新題", update.title)
        assertEquals("新棚", update.location)
    }

    @Test
    fun `conflicting copy ID and ISBN references are rejected`() {
        val first = book(copyId = "copy-1", isbn13 = "9784820418078")
        val second = book(copyId = "copy-2", isbn13 = "9784101010014")

        val result = planner.preview(
            batch(record(copyId = first.copyId, isbn13 = second.isbn13)),
            listOf(first, second),
            ImportConflictPolicy.UPDATE_EXISTING,
        ) as ImportPreviewResult.Invalid

        assertTrue(result.errors.any { it.field == "isbn13" && it.reason.contains("異なる既存蔵書") })
    }

    private fun batch(vararg records: UnvalidatedLibraryBook) = LibraryImportBatch(
        sourceSizeBytes = 1,
        records = records.toList(),
    )

    private fun record(
        copyId: String = "copy-1",
        workId: String = "work-1",
        editionId: String = "edition-1",
        title: String = "本の題名",
        isbn13: String = "9784820418078",
        classificationSource: String = "NDL",
        mediaType: String = "PHYSICAL",
        location: String = "本棚A",
        addedAt: Long = ADDED_AT,
        coverUrl: String? = null,
    ) = UnvalidatedLibraryBook(
        copyId = copyId,
        workId = workId,
        editionId = editionId,
        title = title,
        primaryAuthor = "著者",
        isbn13 = isbn13,
        publisher = null,
        publishedYear = 2024,
        coverUrl = coverUrl,
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        classificationSource = classificationSource,
        mediaType = mediaType,
        location = location,
        readingStatus = "UNREAD",
        addedAt = addedAt,
    )

    private fun book(
        copyId: String = "copy-existing",
        workId: String = "work-existing",
        editionId: String = "edition-existing",
        title: String = "本の題名",
        isbn13: String = "9784820418078",
        location: String = "本棚A",
    ) = LibraryBook(
        copyId = copyId,
        workId = workId,
        editionId = editionId,
        title = title,
        primaryAuthor = "著者",
        isbn13 = isbn13,
        publisher = null,
        publishedYear = 2024,
        coverUrl = null,
        ndcCode = "014.45",
        ndcEdition = "NDC10",
        classificationSource = ClassificationSource.NDL,
        mediaType = MediaType.PHYSICAL,
        location = location,
        readingStatus = ReadingStatus.UNREAD,
        addedAt = ADDED_AT,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val ADDED_AT = 1_700_000_000_000L
        const val TWO_DAYS = 2 * 24 * 60 * 60 * 1000L
    }
}
