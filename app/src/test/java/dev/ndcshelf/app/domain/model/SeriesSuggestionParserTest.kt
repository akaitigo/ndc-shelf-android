package dev.ndcshelf.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSuggestionParserTest {
    @Test
    fun parsesJapaneseAndNumericVolumeFixturesWithoutChangingOriginalLabels() {
        val fixtures = listOf(
            Fixture("宇宙兄弟 第42巻", "宇宙兄弟", "第42巻", SeriesSuggestionConfidence.HIGH),
            Fixture("薬屋のひとりごと１２巻", "薬屋のひとりごと", "１２巻", SeriesSuggestionConfidence.HIGH),
            Fixture("本好きの下剋上 3.5巻", "本好きの下剋上", "3.5巻", SeriesSuggestionConfidence.HIGH),
            Fixture("十二国記 上巻", "十二国記", "上巻", SeriesSuggestionConfidence.HIGH),
            Fixture("長編　後編", "長編", "後編", SeriesSuggestionConfidence.HIGH),
            Fixture("銀河史 7", "銀河史", "7", SeriesSuggestionConfidence.MEDIUM),
        )

        fixtures.forEachIndexed { index, fixture ->
            val suggestion = requireNotNull(SeriesSuggestionParser.suggest("work-$index", fixture.title))
            assertEquals(fixture.series, suggestion.proposedSeriesName)
            assertEquals(fixture.label, suggestion.proposedVolumeLabel)
            assertEquals(fixture.confidence, suggestion.confidence)
            assertEquals(SeriesMembershipType.MAIN_STORY, suggestion.proposedType)
            assertTrue(suggestion.requiresUserConfirmation)
        }
    }

    @Test
    fun parsesRomanNumeralsAndMarksSideStoriesAsLowConfidence() {
        val roman = requireNotNull(SeriesSuggestionParser.suggest("roman", "王国年代記 Ⅳ"))
        val sideStory = requireNotNull(SeriesSuggestionParser.suggest("side", "王国年代記・外伝 北方篇"))

        assertEquals("Ⅳ", roman.proposedVolumeLabel)
        assertEquals(SeriesSuggestionRule.ROMAN_NUMERAL_SUFFIX, roman.rule)
        assertEquals(SeriesSuggestionConfidence.MEDIUM, roman.confidence)
        assertEquals(4.0, roman.orderHint)
        assertEquals("王国年代記", sideStory.proposedSeriesName)
        assertEquals("外伝 北方篇", sideStory.proposedVolumeLabel)
        assertEquals(SeriesMembershipType.SIDE_STORY, sideStory.proposedType)
        assertEquals(SeriesSuggestionConfidence.LOW, sideStory.confidence)
        assertTrue(sideStory.requiresUserConfirmation)
    }

    @Test
    fun orderHintsNormalizeFullWidthDecimalPartsAndRomanNumerals() {
        assertEquals(12.0, "第１２巻".toSeriesOrderHint())
        assertEquals(3.5, "３．５巻".toSeriesOrderHint())
        assertEquals(9.0, "IX".toSeriesOrderHint())
        assertEquals(0.0, "上巻".toSeriesOrderHint())
        assertNull("外伝".toSeriesOrderHint())
    }

    @Test
    fun ambiguousOrMalformedTitlesDoNotBecomeSuggestions() {
        listOf(
            "1984",
            "三体",
            "上下関係の心理学",
            "外伝",
            "シリーズ 章一",
            "",
        ).forEachIndexed { index, title ->
            assertNull(title, SeriesSuggestionParser.suggest("work-$index", title))
        }
        assertNull(SeriesSuggestionParser.suggest("", "作品 1巻"))
    }

    @Test
    fun suggestionsArePlainProposalsAndNeverConfirmedMemberships() {
        val suggestion = requireNotNull(SeriesSuggestionParser.suggest("work", "作品 1巻"))

        assertTrue(suggestion.requiresUserConfirmation)
        assertEquals("作品 1巻", suggestion.sourceTitle)
    }

    private data class Fixture(
        val title: String,
        val series: String,
        val label: String,
        val confidence: SeriesSuggestionConfidence,
    )
}
