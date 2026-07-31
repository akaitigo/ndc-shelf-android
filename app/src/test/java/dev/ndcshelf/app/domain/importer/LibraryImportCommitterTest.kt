package dev.ndcshelf.app.domain.importer

import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.ReadingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class LibraryImportCommitterTest {
    @Test
    fun `stale preview is rejected before writes`() =
        runBlocking {
            val preview = preview(existing = listOf(book("existing")), additions = listOf(book("new")))
            var writes = 0
            val committer =
                LibraryImportCommitter(
                    readCurrentBooks = { listOf(book("changed")) },
                    runInTransaction = { it() },
                    writeBooks = { _, _ -> writes += 1 },
                )

            val result = committer.commit(preview)

            assertSame(ImportApplyResult.StalePreview, result)
            assertEquals(0, writes)
        }

    @Test
    fun `partial write failure rolls back the transaction`() =
        runBlocking {
            val existing = book("existing")
            val addition = book("new")
            var stored = listOf(existing)
            val committer =
                LibraryImportCommitter(
                    readCurrentBooks = { stored },
                    runInTransaction = { block ->
                        val before = stored
                        try {
                            block()
                        } catch (error: Exception) {
                            stored = before
                            throw error
                        }
                    },
                    writeBooks = { books, _ ->
                        stored = stored + books.first()
                        error("2件目の書き込みに失敗")
                    },
                )

            val result = committer.commit(preview(listOf(existing), listOf(addition)))

            assertEquals(listOf(existing), stored)
            assertEquals("蔵書のインポートに失敗しました", (result as ImportApplyResult.Failure).message)
        }

    @Test
    fun `cancellation rolls back and is propagated`() =
        runBlocking {
            val existing = book("existing")
            var stored = listOf(existing)
            val cancellation = CancellationException("cancelled")
            val committer =
                LibraryImportCommitter(
                    readCurrentBooks = { stored },
                    runInTransaction = { block ->
                        val before = stored
                        try {
                            block()
                        } catch (error: Exception) {
                            stored = before
                            throw error
                        }
                    },
                    writeBooks = { books, _ ->
                        stored = stored + books
                        throw cancellation
                    },
                )

            try {
                committer.commit(preview(listOf(existing), listOf(book("new"))))
                fail("CancellationException expected")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
            assertEquals(listOf(existing), stored)
        }

    @Test
    fun `successful commit reports preview counts`() =
        runBlocking {
            val existing = book("existing")
            val addition = book("new")
            var stored = listOf(existing)
            val committer =
                LibraryImportCommitter(
                    readCurrentBooks = { stored },
                    runInTransaction = { it() },
                    writeBooks = { books, _ -> stored = stored + books },
                )

            val result =
                committer.commit(
                    LibraryImportPreview(
                        additions = listOf(addition),
                        updates = emptyList(),
                        skippedCount = 2,
                        conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
                        existingSnapshot = listOf(existing),
                    ),
                ) as ImportApplyResult.Applied

            assertEquals(1, result.addedCount)
            assertEquals(0, result.updatedCount)
            assertEquals(2, result.skippedCount)
            assertEquals(listOf(existing, addition), stored)
        }

    private fun preview(
        existing: List<LibraryBook>,
        additions: List<LibraryBook>,
    ) = LibraryImportPreview(
        additions = additions,
        updates = emptyList(),
        skippedCount = 0,
        conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
        existingSnapshot = existing,
    )

    private fun book(id: String) =
        LibraryBook(
            copyId = "copy-$id",
            workId = "work-$id",
            editionId = "edition-$id",
            title = "本$id",
            primaryAuthor = "著者",
            isbn13 = if (id == "existing") "9784820418078" else "9784101010014",
            publisher = null,
            publishedYear = 2024,
            coverUrl = null,
            ndcCode = "014.45",
            ndcEdition = "NDC10",
            classificationSource = ClassificationSource.NDL,
            mediaType = MediaType.PHYSICAL,
            location = "本棚A",
            readingStatus = ReadingStatus.UNREAD,
            addedAt = 1_700_000_000_000L,
        )
}
