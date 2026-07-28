package dev.ndcshelf.app.data.repository

import java.security.MessageDigest

internal object FractionalOrderKey {
    private const val MIN_DIGIT = 0
    private const val MAX_DIGIT = 255
    private const val ENTROPY_BYTES = 8
    private const val COMPACT_KEY_LENGTH = 16

    const val COMPACTION_THRESHOLD = 64
    const val MAX_GENERATED_LENGTH = COMPACTION_THRESHOLD + (ENTROPY_BYTES + 1) * 2

    fun between(left: String?, right: String?, uniqueSeed: String): String {
        require(left == null || right == null || left < right) {
            "Left order key must sort before right order key"
        }
        val leftBytes = left?.decodeHex().orEmpty()
        val rightBytes = right?.decodeHex().orEmpty()
        val prefix = ArrayList<Int>()
        var index = 0
        while (true) {
            val lower = leftBytes.getOrNull(index)?.toInt()?.and(0xff) ?: MIN_DIGIT
            val upper = rightBytes.getOrNull(index)?.toInt()?.and(0xff) ?: MAX_DIGIT
            if (upper - lower > 1) {
                prefix += lower + (upper - lower) / 2
                break
            }
            prefix += lower
            index += 1
        }
        val entropy = MessageDigest.getInstance("SHA-256")
            .digest(uniqueSeed.encodeToByteArray())
            .take(ENTROPY_BYTES)
        return (prefix + entropy.map { it.toInt() and 0xff })
            .joinToString("") { value -> value.toString(16).padStart(2, '0') }
    }

    fun requiresCompaction(keys: List<String?>): Boolean =
        keys.any { it == null || it.length > COMPACTION_THRESHOLD }

    fun compact(index: Int, total: Int): String {
        require(total > 0 && index in 0 until total)
        val spacing = Long.MAX_VALUE / (total.toLong() + 1)
        return (spacing * (index + 1)).toString(16).padStart(COMPACT_KEY_LENGTH, '0')
    }

    private fun String.decodeHex(): List<Byte> {
        require(length % 2 == 0 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Order key must be even-length hexadecimal"
        }
        return chunked(2).map { it.toInt(16).toByte() }
    }

}
