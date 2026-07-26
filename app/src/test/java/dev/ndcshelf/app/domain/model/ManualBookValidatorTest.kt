package dev.ndcshelf.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ManualBookValidatorTest {
    private val validator = ManualBookValidator { Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() }

    @Test
    fun titleOnly_isValidAndNormalizesOptionalValues() {
        val result = validator.validate(ManualBookDraft(title = "  郷土資料  "))
            as ManualBookValidationResult.Valid

        assertEquals("郷土資料", result.book.title)
        assertEquals("著者不明", result.book.primaryAuthor)
        assertNull(result.book.isbn13)
        assertNull(result.book.publishedYear)
    }

    @Test
    fun optionalIsbn_isNormalizedToIsbn13() {
        val result = validator.validate(
            ManualBookDraft(title = "本", isbn = "0-306-40615-2"),
        ) as ManualBookValidationResult.Valid

        assertEquals("9780306406157", result.book.isbn13)
    }

    @Test
    fun invalidFields_areReportedWithoutPartialValue() {
        val result = validator.validate(
            ManualBookDraft(title = " ", isbn = "9780000000000", ndcCode = "91A", publishedYear = "3000"),
        ) as ManualBookValidationResult.Invalid

        assertTrue(result.errors.any { it.field == ManualBookField.TITLE })
        assertTrue(result.errors.any { it.field == ManualBookField.ISBN })
        assertTrue(result.errors.any { it.field == ManualBookField.NDC_CODE })
        assertTrue(result.errors.any { it.field == ManualBookField.PUBLISHED_YEAR })
    }
}
