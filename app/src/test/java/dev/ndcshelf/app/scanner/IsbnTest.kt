package dev.ndcshelf.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsbnTest {
    @Test
    fun `accepts valid isbn13 with separators`() {
        assertEquals(
            "9784820418078",
            Isbn.normalizeToIsbn13("978-4-8204-1807-8"),
        )
    }

    @Test
    fun `converts valid isbn10 to isbn13`() {
        assertEquals(
            "9780306406157",
            Isbn.normalizeToIsbn13("0-306-40615-2"),
        )
    }

    @Test
    fun `rejects invalid check digit`() {
        assertFalse(Isbn.isValidIsbn13("9780306406158"))
        assertNull(Isbn.normalizeToIsbn13("9780306406158"))
    }

    @Test
    fun `rejects non book ean`() {
        assertFalse(Isbn.isValidIsbn13("4901234567894"))
    }

    @Test
    fun `accepts bookland prefixes`() {
        assertTrue(Isbn.isValidIsbn13("9784820418078"))
        assertTrue(Isbn.isValidIsbn13("9791090636071"))
    }
}
