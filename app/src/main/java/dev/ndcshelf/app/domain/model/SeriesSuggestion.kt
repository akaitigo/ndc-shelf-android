package dev.ndcshelf.app.domain.model

data class SeriesSuggestion(
    val workId: String,
    val sourceTitle: String,
    val proposedSeriesName: String,
    val proposedVolumeLabel: String,
    val proposedType: SeriesMembershipType,
    val confidence: SeriesSuggestionConfidence,
    val rule: SeriesSuggestionRule,
    val orderHint: Double? = proposedVolumeLabel.toSeriesOrderHint(),
) {
    val requiresUserConfirmation: Boolean = true
}

internal fun String.toSeriesOrderHint(): Double? {
    val normalized = trim()
        .map { character ->
            when (character) {
                in '０'..'９' -> '0' + (character - '０')
                '．' -> '.'
                else -> character
            }
        }
        .joinToString("")
        .removePrefix("第")
        .removeSuffix("巻")
    normalized.toDoubleOrNull()?.let { return it }
    return when (normalized) {
        "上", "前編" -> 0.0
        "中" -> 0.5
        "下", "後編" -> 1.0
        else -> normalized.toRomanNumber()?.toDouble()
    }
}

private fun String.toRomanNumber(): Int? {
    val ascii = map { character ->
        when (character) {
            'Ⅰ' -> 'I'; 'Ⅱ' -> '2'; 'Ⅲ' -> '3'; 'Ⅳ' -> '4'; 'Ⅴ' -> '5'
            'Ⅵ' -> '6'; 'Ⅶ' -> '7'; 'Ⅷ' -> '8'; 'Ⅸ' -> '9'; 'Ⅹ' -> 'X'
            else -> character.uppercaseChar()
        }
    }.joinToString("")
    ascii.toIntOrNull()?.let { return it }
    if (ascii.any { it !in "IVXLCDM" }) return null
    var total = 0
    var previous = 0
    ascii.reversed().forEach { character ->
        val value = when (character) {
            'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50
            'C' -> 100; 'D' -> 500; 'M' -> 1000
            else -> return null
        }
        if (value < previous) total -= value else {
            total += value
            previous = value
        }
    }
    return total.takeIf { it > 0 }
}

enum class SeriesSuggestionConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

enum class SeriesSuggestionRule {
    EXPLICIT_VOLUME,
    NUMBER_SUFFIX,
    PART_SUFFIX,
    ROMAN_NUMERAL_SUFFIX,
    SIDE_STORY_SUFFIX,
    MANUAL_ENTRY,
}

object SeriesSuggestionParser {
    fun suggest(workId: String, title: String): SeriesSuggestion? {
        val sourceTitle = title.trim()
        if (workId.isBlank() || sourceTitle.isBlank()) return null

        val match = RULES.firstNotNullOfOrNull { rule ->
            rule.pattern.matchEntire(sourceTitle)?.let { result -> rule to result }
        } ?: return null
        val (rule, result) = match
        val seriesName = result.groupValues[1].trim().trimEnd('・', '-', '－', ':', '：').trim()
        val volumeLabel = result.groupValues[2].trim()
        if (seriesName.isBlank() || volumeLabel.isBlank()) return null

        return SeriesSuggestion(
            workId = workId,
            sourceTitle = sourceTitle,
            proposedSeriesName = seriesName,
            proposedVolumeLabel = volumeLabel,
            proposedType = rule.type,
            confidence = rule.confidence,
            rule = rule.rule,
        )
    }

    fun manual(workId: String, title: String): SeriesSuggestion? {
        val sourceTitle = title.trim()
        if (workId.isBlank() || sourceTitle.isBlank()) return null
        return SeriesSuggestion(
            workId = workId,
            sourceTitle = sourceTitle,
            proposedSeriesName = sourceTitle,
            proposedVolumeLabel = "巻番号なし",
            proposedType = SeriesMembershipType.OTHER,
            confidence = SeriesSuggestionConfidence.LOW,
            rule = SeriesSuggestionRule.MANUAL_ENTRY,
        )
    }

    private data class Rule(
        val pattern: Regex,
        val type: SeriesMembershipType,
        val confidence: SeriesSuggestionConfidence,
        val rule: SeriesSuggestionRule,
    )

    private val RULES = listOf(
        Rule(
            pattern = Regex("^(.+?)[\\s　]*(第?[0-9０-９]+(?:[.．][0-9０-９]+)?巻)$"),
            type = SeriesMembershipType.MAIN_STORY,
            confidence = SeriesSuggestionConfidence.HIGH,
            rule = SeriesSuggestionRule.EXPLICIT_VOLUME,
        ),
        Rule(
            pattern = Regex("^(.+?)[\\s　]+([上下中](?:巻)?|前編|後編)$"),
            type = SeriesMembershipType.MAIN_STORY,
            confidence = SeriesSuggestionConfidence.HIGH,
            rule = SeriesSuggestionRule.PART_SUFFIX,
        ),
        Rule(
            pattern = Regex("^(.+?)[\\s　]+([0-9０-９]+(?:[.．][0-9０-９]+)?)$"),
            type = SeriesMembershipType.MAIN_STORY,
            confidence = SeriesSuggestionConfidence.MEDIUM,
            rule = SeriesSuggestionRule.NUMBER_SUFFIX,
        ),
        Rule(
            pattern = Regex("^(.+?)[\\s　]+([IVXLCDMⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+)$", RegexOption.IGNORE_CASE),
            type = SeriesMembershipType.MAIN_STORY,
            confidence = SeriesSuggestionConfidence.MEDIUM,
            rule = SeriesSuggestionRule.ROMAN_NUMERAL_SUFFIX,
        ),
        Rule(
            pattern = Regex("^(.+?)[\\s　・:：-]+(外伝(?:[\\s　].*)?)$"),
            type = SeriesMembershipType.SIDE_STORY,
            confidence = SeriesSuggestionConfidence.LOW,
            rule = SeriesSuggestionRule.SIDE_STORY_SUFFIX,
        ),
    )
}
