package dev.ndcshelf.app.scanner

object Isbn {
    fun normalizeToIsbn13(raw: String): String? {
        val compact = raw
            .uppercase()
            .filter { it.isDigit() || it == 'X' }

        return when {
            compact.length == 13 && isValidIsbn13(compact) -> compact
            compact.length == 10 && isValidIsbn10(compact) -> {
                val firstTwelve = "978${compact.take(9)}"
                firstTwelve + isbn13CheckDigit(firstTwelve)
            }

            else -> null
        }
    }

    fun isValidIsbn13(value: String): Boolean {
        if (value.length != 13 || value.any { !it.isDigit() }) return false
        if (!value.startsWith("978") && !value.startsWith("979")) return false

        val expected = isbn13CheckDigit(value.take(12))
        return value.last().digitToInt() == expected
    }

    private fun isValidIsbn10(value: String): Boolean {
        if (value.length != 10) return false
        if (value.take(9).any { !it.isDigit() }) return false
        if (!value.last().isDigit() && value.last() != 'X') return false

        val sum = value.mapIndexed { index, character ->
            val digit = if (character == 'X') 10 else character.digitToInt()
            (10 - index) * digit
        }.sum()
        return sum % 11 == 0
    }

    private fun isbn13CheckDigit(firstTwelve: String): Int {
        val sum = firstTwelve.mapIndexed { index, character ->
            character.digitToInt() * if (index % 2 == 0) 1 else 3
        }.sum()
        return (10 - (sum % 10)) % 10
    }
}
