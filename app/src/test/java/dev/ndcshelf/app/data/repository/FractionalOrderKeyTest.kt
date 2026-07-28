package dev.ndcshelf.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class FractionalOrderKeyTest {
    @Test
    fun keysCanBeCreatedAtStartMiddleAndEnd() {
        val middle = FractionalOrderKey.between(null, null, "middle")
        val first = FractionalOrderKey.between(null, middle, "first")
        val last = FractionalOrderKey.between(middle, null, "last")

        assertTrue(first < middle)
        assertTrue(middle < last)
        val between = FractionalOrderKey.between(first, middle, "between")
        assertTrue(between > first && between < middle)
    }

    @Test
    fun concurrentInsertionsUseSeedEntropyAsStableTieBreaker() {
        val first = FractionalOrderKey.between("40", "80", "copy-a")
        val second = FractionalOrderKey.between("40", "80", "copy-b")

        assertNotEquals(first, second)
        assertEquals(first, FractionalOrderKey.between("40", "80", "copy-a"))
        assertTrue(first > "40" && first < "80")
        assertTrue(second > "40" && second < "80")
    }

    @Test
    fun tenThousandRepeatedInsertionsRemainOrderedWithBoundedKeyLength() {
        val keys = mutableListOf<String>()
        val elapsed = measureTimeMillis {
            repeat(10_000) { index ->
                if (FractionalOrderKey.requiresCompaction(keys)) {
                    keys.indices.forEach { keyIndex ->
                        keys[keyIndex] = FractionalOrderKey.compact(keyIndex, keys.size)
                    }
                }
                val left = keys.lastOrNull()
                keys += FractionalOrderKey.between(left, null, "copy-$index")
            }
        }

        assertEquals(keys.sorted(), keys)
        assertEquals(10_000, keys.distinct().size)
        assertTrue(keys.maxOf(String::length) <= FractionalOrderKey.MAX_GENERATED_LENGTH)
        assertTrue("10,000 keys took ${elapsed}ms", elapsed < 5_000)
    }
}
