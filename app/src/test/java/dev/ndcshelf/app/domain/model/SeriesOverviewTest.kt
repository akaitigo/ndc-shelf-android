package dev.ndcshelf.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesOverviewTest {
    @Test
    fun missingCandidatesUseOnlyConfirmedMainStoryWithExplicitSequence() {
        val overview = overview(
            volume("1巻", SeriesMembershipType.MAIN_STORY, owned = true),
            volume("3巻", SeriesMembershipType.MAIN_STORY),
            volume("外伝", SeriesMembershipType.SIDE_STORY),
            volume("1-2巻", SeriesMembershipType.OMNIBUS),
            volume("巻番号なし", SeriesMembershipType.MAIN_STORY),
        )

        assertEquals(1, overview.missingCandidateCount)
        assertEquals("3巻", overview.volumes.single { it.isMissingCandidate }.membership.volumeLabel)
        assertFalse(overview.isConfirmedMainStoryComplete)
    }

    @Test
    fun sequenceRecognitionIsConservativeAndDoesNotInventNumberGaps() {
        listOf("1", "１巻", "第1巻", "1.5巻", "上巻", "中", "下巻", "前編", "後編").forEach {
            assertTrue(it, it.isExplicitSeriesSequence())
        }
        listOf("外伝", "1-2巻", "巻番号なし", "").forEach {
            assertFalse(it, it.isExplicitSeriesSequence())
        }

        val overview = overview(
            volume("1巻", SeriesMembershipType.MAIN_STORY, owned = true),
            volume("3巻", SeriesMembershipType.MAIN_STORY, owned = true),
        )
        assertEquals(2, overview.knownVolumeCount)
        assertEquals(0, overview.missingCandidateCount)
        assertTrue(overview.isConfirmedMainStoryComplete)
    }

    @Test
    fun ownedStateTakesPriorityAndSummaryCountsVolumesNotCopies() {
        val owned = volume(
            label = "2巻",
            type = SeriesMembershipType.MAIN_STORY,
            owned = true,
            ownedCopies = 2,
            readCopies = 1,
            purchaseStatus = PurchaseStatus.RESERVED,
        )
        val overview = overview(owned)

        assertEquals(SeriesVolumeState.OWNED, owned.state)
        assertEquals(1, overview.ownedVolumeCount)
        assertEquals(1, overview.readVolumeCount)
        assertEquals(owned, overview.latestOwnedVolume)
    }

    private fun overview(vararg volumes: SeriesVolume) = SeriesOverview(
        series = BookSeries("series", "長編", 1, 2),
        volumes = volumes.toList(),
    )

    private fun volume(
        label: String,
        type: SeriesMembershipType,
        owned: Boolean = false,
        ownedCopies: Int = if (owned) 1 else 0,
        readCopies: Int = 0,
        purchaseStatus: PurchaseStatus? = null,
    ) = SeriesVolume(
        membership = SeriesMembership(
            id = "membership-$label",
            seriesId = "series",
            workId = "work-$label",
            workTitle = "作品 $label",
            primaryAuthor = "著者",
            sortOrderKey = label,
            volumeLabel = label,
            type = type,
            createdAt = 1,
            updatedAt = 2,
        ),
        ownedEditionId = "edition-$label".takeIf { owned },
        bookstoreIsbn = "9784000000000",
        ownedCopyCount = ownedCopies,
        readCopyCount = readCopies,
        readingCopyCount = 0,
        purchaseStatus = purchaseStatus,
        latestOwnedAddedAt = 3L.takeIf { owned },
    )
}
