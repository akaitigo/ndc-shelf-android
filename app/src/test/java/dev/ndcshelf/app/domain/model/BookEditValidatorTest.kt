package dev.ndcshelf.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookEditValidatorTest {
    private val validator = BookEditValidator(nowMillis = { 1_735_689_600_000L })

    @Test
    fun `valid edit trims required values and normalizes blank optional values`() {
        val result = validator.validate(
            draft(
                title = "  本の題名  ",
                primaryAuthor = "  著者  ",
                publisher = "   ",
                publishedYear = " 2024 ",
                ndcCode = " 014.45 ",
                ndcEdition = " NDC10 ",
                location = " 本棚A ",
            ),
        ) as BookEditValidationResult.Valid

        assertEquals("本の題名", result.edit.title)
        assertEquals("著者", result.edit.primaryAuthor)
        assertNull(result.edit.publisher)
        assertEquals(2024, result.edit.publishedYear)
        assertEquals("014.45", result.edit.ndcCode)
        assertEquals("NDC10", result.edit.ndcEdition)
        assertEquals("本棚A", result.edit.location)
    }

    @Test
    fun `required year and NDC errors identify their fields`() {
        val result = validator.validate(
            draft(
                title = " ",
                primaryAuthor = "",
                publishedYear = "2027",
                ndcCode = "14.5",
                location = "",
            ),
        ) as BookEditValidationResult.Invalid

        assertTrue(result.errors.any { it.field == BookEditField.TITLE })
        assertTrue(result.errors.any { it.field == BookEditField.PRIMARY_AUTHOR })
        assertTrue(result.errors.any { it.field == BookEditField.PUBLISHED_YEAR })
        assertTrue(result.errors.any { it.field == BookEditField.NDC_CODE })
        assertTrue(result.errors.any { it.field == BookEditField.LOCATION })
    }

    @Test
    fun `overlong and NUL text values are rejected`() {
        val result = validator.validate(
            draft(
                title = "x".repeat(2_001),
                primaryAuthor = "著者\u0000名",
            ),
        ) as BookEditValidationResult.Invalid

        assertTrue(result.errors.any { it.field == BookEditField.TITLE && it.reason.contains("2000") })
        assertTrue(result.errors.any { it.field == BookEditField.PRIMARY_AUTHOR && it.reason.contains("NUL") })
    }

    private fun draft(
        title: String = "本の題名",
        primaryAuthor: String = "著者",
        publisher: String = "出版社",
        publishedYear: String = "2024",
        ndcCode: String = "014.45",
        ndcEdition: String = "NDC10",
        location: String = "本棚A",
    ) = BookEditDraft(
        title = title,
        primaryAuthor = primaryAuthor,
        publisher = publisher,
        publishedYear = publishedYear,
        ndcCode = ndcCode,
        ndcEdition = ndcEdition,
        location = location,
        readingStatus = ReadingStatus.READING,
    )
}
