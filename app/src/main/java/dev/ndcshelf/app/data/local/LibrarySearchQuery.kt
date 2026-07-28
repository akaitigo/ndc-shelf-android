package dev.ndcshelf.app.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.ndcshelf.app.domain.model.LibrarySearchCriteria
import dev.ndcshelf.app.domain.model.LibrarySort

internal fun LibrarySearchCriteria.toSQLiteQuery(): SupportSQLiteQuery {
    val conditions = mutableListOf<String>()
    val arguments = mutableListOf<Any>()

    if (selectedEditionId == null) {
        normalizedQuery.takeIf(String::isNotEmpty)?.let { query ->
            conditions += """
                (
                    works.title LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    works.primaryAuthor LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    editions.isbn13 LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    editions.ndcCode LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    copies.copyLabel LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    copies.location LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    rooms.name LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    shelves.name LIKE ? ESCAPE '\' COLLATE NOCASE OR
                    tiers.name LIKE ? ESCAPE '\' COLLATE NOCASE
                )
            """.trimIndent()
            val pattern = "%${query.escapeLikePattern()}%"
            repeat(9) { arguments += pattern }
        }
        readingStatus?.let { status ->
            conditions += "copies.readingStatus = ?"
            arguments += status.name
        }
    } else {
        // Detail editing needs every edition copy and only its shelves' neighbors for placement.
        conditions += """
            editions.id = ? OR copies.tierId IN (
                SELECT selectedCopies.tierId
                FROM owned_copies AS selectedCopies
                WHERE selectedCopies.editionId = ? AND selectedCopies.tierId IS NOT NULL
            )
        """.trimIndent()
        arguments += selectedEditionId
        arguments += selectedEditionId
    }

    val where = conditions.takeIf(List<String>::isNotEmpty)
        ?.joinToString(prefix = "WHERE ", separator = " AND ")
        .orEmpty()
    val order = when (sort) {
        LibrarySort.ADDED_NEWEST -> "copies.addedAt DESC, copies.id ASC"
        LibrarySort.TITLE -> "works.title COLLATE NOCASE ASC, copies.addedAt DESC, copies.id ASC"
        LibrarySort.AUTHOR ->
            "works.primaryAuthor COLLATE NOCASE ASC, works.title COLLATE NOCASE ASC, copies.id ASC"
        LibrarySort.NDC ->
            "editions.ndcCode IS NULL ASC, editions.ndcCode ASC, works.title COLLATE NOCASE ASC, copies.id ASC"
        LibrarySort.SHELF -> """
            copies.tierId IS NULL ASC,
            rooms.sortOrder ASC,
            shelves.sortOrder ASC,
            tiers.sortOrder ASC,
            CASE WHEN copies.tierId IS NULL THEN copies.addedAt END ASC,
            copies.shelfOrderKey IS NULL ASC,
            copies.shelfOrderKey ASC,
            copies.addedAt ASC,
            copies.id ASC
        """.trimIndent()
    }
    return SimpleSQLiteQuery("$LIBRARY_SELECT\n$where\nORDER BY $order", arguments.toTypedArray())
}

private fun String.escapeLikePattern(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private val LIBRARY_SELECT = """
    SELECT
        copies.id AS copyId,
        works.id AS workId,
        editions.id AS editionId,
        works.title AS title,
        works.primaryAuthor AS primaryAuthor,
        editions.isbn13 AS isbn13,
        editions.publisher AS publisher,
        editions.publishedYear AS publishedYear,
        editions.coverUrl AS coverUrl,
        editions.ndcCode AS ndcCode,
        editions.ndcEdition AS ndcEdition,
        editions.classificationSource AS classificationSource,
        editions.bibliographicSource AS bibliographicSource,
        copies.mediaType AS mediaType,
        CASE WHEN tiers.id IS NULL THEN copies.location
            ELSE rooms.name || ' / ' || shelves.name || ' / ' || tiers.name
        END AS location,
        copies.tierId AS locationTierId,
        copies.readingStatus AS readingStatus,
        copies.addedAt AS addedAt,
        copies.shelfOrderKey AS shelfOrderKey,
        copies.copyLabel AS copyLabel
    FROM owned_copies AS copies
    INNER JOIN book_editions AS editions ON editions.id = copies.editionId
    INNER JOIN book_works AS works ON works.id = editions.workId
    LEFT JOIN location_tiers AS tiers ON tiers.id = copies.tierId
    LEFT JOIN location_shelves AS shelves ON shelves.id = tiers.shelfId
    LEFT JOIN location_rooms AS rooms ON rooms.id = shelves.roomId
""".trimIndent()
