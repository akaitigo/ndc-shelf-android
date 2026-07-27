package dev.ndcshelf.app.data.repository

import dev.ndcshelf.app.data.local.SeriesEntity
import dev.ndcshelf.app.data.local.SeriesMembershipRow
import dev.ndcshelf.app.domain.model.SeriesMembershipType
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesMappingsTest {
    @Test
    fun entityAndMembershipRowMapWithoutLosingStableIdentifiers() {
        val series = SeriesEntity("series-1", "作品集", 10, 20).toDomain()
        val membership = SeriesMembershipRow(
            membershipId = "membership-1",
            seriesId = "series-1",
            workId = "work-1",
            workTitle = "外伝",
            primaryAuthor = "著者",
            sortOrderKey = "80",
            volumeLabel = "外伝",
            type = "SIDE_STORY",
            createdAt = 11,
            updatedAt = 21,
        ).toDomain()

        assertEquals("series-1", series.id)
        assertEquals("作品集", series.name)
        assertEquals("membership-1", membership.id)
        assertEquals("series-1", membership.seriesId)
        assertEquals("work-1", membership.workId)
        assertEquals("80", membership.sortOrderKey)
        assertEquals(SeriesMembershipType.SIDE_STORY, membership.type)
    }
}
