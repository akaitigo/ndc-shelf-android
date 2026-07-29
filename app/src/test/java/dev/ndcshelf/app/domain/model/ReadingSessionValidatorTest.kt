package dev.ndcshelf.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingSessionValidatorTest {
    private val validator = ReadingSessionValidator()

    @Test
    fun `partial dates accept year month and day precision`() {
        assertEquals(PartialDate(2026), PartialDate.parse("2026"))
        assertEquals(PartialDate(2026, 7), PartialDate.parse("2026-07"))
        assertEquals(PartialDate(2026, 7, 29), PartialDate.parse("2026-07-29"))
        assertEquals("2026-07-29", PartialDate(2026, 7, 29).format())
        assertEquals("2026-07", PartialDate(2026, 7).format())
        assertEquals("2026", PartialDate(2026).format())
    }

    @Test
    fun `partial dates reject malformed and impossible calendar days`() {
        listOf(
            "26",
            "2026-7",
            "2026-07-9",
            "2026/07/29",
            "2026-00",
            "2026-13",
            "2026-07-00",
            "2026-07-32",
            "2026-02-29",
            "2100-02-29",
            "0999",
            "not-a-date",
            "2026-07-29T00:00",
        ).forEach { raw ->
            assertNull("should reject $raw", PartialDate.parse(raw))
        }
        // うるう年の2月29日は受理する（2000年は400年ルールで閏年）。
        assertEquals(PartialDate(2024, 2, 29), PartialDate.parse("2024-02-29"))
        assertEquals(PartialDate(2000, 2, 29), PartialDate.parse("2000-02-29"))
    }

    @Test
    fun `shared precision comparison does not assume order for coarser dates`() {
        val year = PartialDate(2026)
        val month = PartialDate(2026, 1)
        val day = PartialDate(2026, 1, 15)
        assertEquals(0, year.compareAtSharedPrecision(day))
        assertEquals(0, month.compareAtSharedPrecision(day))
        assertTrue(PartialDate(2025).compareAtSharedPrecision(day) < 0)
        assertTrue(PartialDate(2026, 2).compareAtSharedPrecision(day) > 0)
        assertTrue(
            PartialDate(2026, 1, 16).compareAtSharedPrecision(day) > 0,
        )
    }

    @Test
    fun `unknown dates are allowed and finished before started is rejected`() {
        val unknown =
            validator.validate(
                ReadingSessionDraft(status = ReadingSessionStatus.FINISHED),
            ) as ReadingSessionValidationResult.Valid
        assertNull(unknown.session.startedDay)
        assertNull(unknown.session.finishedDay)

        val invalid =
            validator.validate(
                ReadingSessionDraft(
                    status = ReadingSessionStatus.FINISHED,
                    startedDay = "2026-07-29",
                    finishedDay = "2026-07-28",
                ),
            ) as ReadingSessionValidationResult.Invalid
        assertTrue(invalid.errors.any { it.field == ReadingSessionField.FINISHED_DAY })

        // 精度が異なる場合は共通精度で比較し、同値なら許容する。
        val coarse =
            validator.validate(
                ReadingSessionDraft(
                    status = ReadingSessionStatus.FINISHED,
                    startedDay = "2026-07-29",
                    finishedDay = "2026",
                ),
            )
        assertTrue(coarse is ReadingSessionValidationResult.Valid)
    }

    @Test
    fun `finished day is rejected for in-progress sessions`() {
        val invalid =
            validator.validate(
                ReadingSessionDraft(
                    status = ReadingSessionStatus.READING,
                    finishedDay = "2026-07-29",
                ),
            ) as ReadingSessionValidationResult.Invalid
        assertTrue(invalid.errors.any { it.field == ReadingSessionField.FINISHED_DAY })
    }

    @Test
    fun `rating range and note limits are enforced`() {
        val invalidRating =
            validator.validate(
                ReadingSessionDraft(status = ReadingSessionStatus.FINISHED, rating = 6),
            ) as ReadingSessionValidationResult.Invalid
        assertTrue(invalidRating.errors.any { it.field == ReadingSessionField.RATING })

        val invalidNote =
            validator.validate(
                ReadingSessionDraft(
                    status = ReadingSessionStatus.FINISHED,
                    note = "あ".repeat(ReadingSessionValidator.MAX_NOTE_LENGTH + 1),
                ),
            ) as ReadingSessionValidationResult.Invalid
        assertTrue(invalidNote.errors.any { it.field == ReadingSessionField.NOTE })

        val valid =
            validator.validate(
                ReadingSessionDraft(
                    status = ReadingSessionStatus.FINISHED,
                    startedDay = " 2026-07 ",
                    finishedDay = "2026-07-29",
                    rating = 5,
                    note = "  面白かった  ",
                ),
            ) as ReadingSessionValidationResult.Valid
        assertEquals(PartialDate(2026, 7), valid.session.startedDay)
        assertEquals(PartialDate(2026, 7, 29), valid.session.finishedDay)
        assertEquals(5, valid.session.rating)
        assertEquals("面白かった", valid.session.note)

        val blankNote =
            validator.validate(
                ReadingSessionDraft(status = ReadingSessionStatus.FINISHED, note = "   "),
            ) as ReadingSessionValidationResult.Valid
        assertNull(blankNote.session.note)
    }
}
