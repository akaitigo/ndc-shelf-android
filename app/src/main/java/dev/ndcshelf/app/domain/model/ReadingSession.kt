package dev.ndcshelf.app.domain.model

import dev.ndcshelf.app.R
import dev.ndcshelf.app.domain.text.UiMessage

/**
 * 読書セッション。開始・中断・再開・読了を1セッションの状態遷移として表し、
 * 再読は同じコピーへの新しいセッション追加として表す。
 *
 * 識別子は端末間で衝突しない独立UUIDで、同期時にもこのIDで識別する。
 */
data class ReadingSession(
    val id: String,
    val copyId: String,
    val copyLabel: String,
    val status: ReadingSessionStatus,
    val startedDay: PartialDate?,
    val finishedDay: PartialDate?,
    val rating: Int?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ReadingSessionStatus {
    READING,
    PAUSED,
    FINISHED,
}

/**
 * 部分日付。"2026"（年のみ）、"2026-07"（年月）、"2026-07-29"（年月日）を表す。
 *
 * 時刻・タイムゾーンを持たないローカル暦日として扱うため、端末の時刻ずれや
 * タイムゾーン変更の影響を受けない。日付不明はnull（PartialDate自体を持たない）で表す。
 */
data class PartialDate(
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
) {
    init {
        require(year in MIN_YEAR..MAX_YEAR)
        require(month == null || month in 1..12)
        require(day == null || (month != null && day in 1..daysInMonth(year, month)))
    }

    /** 保存・表示用の正規形（"2026" / "2026-07" / "2026-07-29"）。 */
    fun format(): String =
        buildString {
            append("%04d".format(year))
            month?.let { append("-%02d".format(it)) }
            day?.let { append("-%02d".format(it)) }
        }

    /**
     * 共通の精度で比較する。片方が年のみの場合は年同士だけを比較し、
     * 同値なら0を返す（部分日付同士の前後関係は断定しない）。
     */
    fun compareAtSharedPrecision(other: PartialDate): Int {
        val byYear = year.compareTo(other.year)
        if (byYear != 0) return byYear
        val thisMonth = month ?: return 0
        val otherMonth = other.month ?: return 0
        val byMonth = thisMonth.compareTo(otherMonth)
        if (byMonth != 0) return byMonth
        val thisDay = day ?: return 0
        val otherDay = other.day ?: return 0
        return thisDay.compareTo(otherDay)
    }

    companion object {
        const val MIN_YEAR = 1000
        const val MAX_YEAR = 9999
        private val PATTERN = Regex("(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?")

        /** 正規形のみ受理する。不正な形式・存在しない暦日はnull。 */
        fun parse(raw: String): PartialDate? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            val year = match.groupValues[1].toInt()
            if (year !in MIN_YEAR..MAX_YEAR) return null
            val month = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            if (month != null && month !in 1..12) return null
            val day = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            if (day != null && (month == null || day !in 1..daysInMonth(year, month))) return null
            return PartialDate(year, month, day)
        }

        fun daysInMonth(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                2 -> if (isLeapYear(year)) 29 else 28
                else -> 0
            }

        private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }
}

/** 読書セッションの入力ドラフト。日付は正規形の文字列または空。 */
data class ReadingSessionDraft(
    val status: ReadingSessionStatus,
    val startedDay: String = "",
    val finishedDay: String = "",
    val rating: Int? = null,
    val note: String = "",
)

data class ValidatedReadingSession(
    val status: ReadingSessionStatus,
    val startedDay: PartialDate?,
    val finishedDay: PartialDate?,
    val rating: Int?,
    val note: String?,
)

enum class ReadingSessionField {
    STATUS,
    STARTED_DAY,
    FINISHED_DAY,
    RATING,
    NOTE,
}

data class ReadingSessionValidationError(
    val field: ReadingSessionField,
    val message: UiMessage,
)

sealed interface ReadingSessionValidationResult {
    data class Valid(
        val session: ValidatedReadingSession,
    ) : ReadingSessionValidationResult

    data class Invalid(
        val errors: List<ReadingSessionValidationError>,
    ) : ReadingSessionValidationResult
}

/**
 * 読書セッション入力の検証。
 *
 * - 日付は不明（空）を許可し、部分日付は正規形のみ受理する。
 * - 開始日と読了日が両方ある場合、共通精度で読了日が開始日より前なら拒否する。
 * - 評価は1〜5、メモは最大2000文字。
 */
class ReadingSessionValidator {
    fun validate(draft: ReadingSessionDraft): ReadingSessionValidationResult {
        val errors = mutableListOf<ReadingSessionValidationError>()

        val startedRaw = draft.startedDay.trim()
        val startedDay = if (startedRaw.isEmpty()) null else PartialDate.parse(startedRaw)
        if (startedRaw.isNotEmpty() && startedDay == null) {
            errors +=
                ReadingSessionValidationError(
                    ReadingSessionField.STARTED_DAY,
                    DATE_FORMAT_MESSAGE,
                )
        }

        val finishedRaw = draft.finishedDay.trim()
        val finishedDay = if (finishedRaw.isEmpty()) null else PartialDate.parse(finishedRaw)
        if (finishedRaw.isNotEmpty() && finishedDay == null) {
            errors +=
                ReadingSessionValidationError(
                    ReadingSessionField.FINISHED_DAY,
                    DATE_FORMAT_MESSAGE,
                )
        }

        if (startedDay != null && finishedDay != null &&
            finishedDay.compareAtSharedPrecision(startedDay) < 0
        ) {
            errors +=
                ReadingSessionValidationError(
                    ReadingSessionField.FINISHED_DAY,
                    UiMessage(R.string.validation_finished_before_started),
                )
        }

        if (draft.status != ReadingSessionStatus.FINISHED && finishedDay != null) {
            errors +=
                ReadingSessionValidationError(
                    ReadingSessionField.FINISHED_DAY,
                    UiMessage(R.string.validation_finished_requires_finished_status),
                )
        }

        if (draft.rating != null && draft.rating !in MIN_RATING..MAX_RATING) {
            errors +=
                ReadingSessionValidationError(
                    ReadingSessionField.RATING,
                    UiMessage(R.string.validation_rating_range, MIN_RATING, MAX_RATING),
                )
        }

        val note = draft.note.trim()
        if (note.length > MAX_NOTE_LENGTH) {
            errors +=
                ReadingSessionValidationError(
                    ReadingSessionField.NOTE,
                    UiMessage(R.string.validation_note_max_length, MAX_NOTE_LENGTH),
                )
        }
        if ('\u0000' in note) {
            errors += ReadingSessionValidationError(
                    ReadingSessionField.NOTE,
                    UiMessage(R.string.validation_note_charset),
                )
        }

        if (errors.isNotEmpty()) return ReadingSessionValidationResult.Invalid(errors)
        return ReadingSessionValidationResult.Valid(
            ValidatedReadingSession(
                status = draft.status,
                startedDay = startedDay,
                finishedDay = finishedDay,
                rating = draft.rating,
                note = note.ifEmpty { null },
            ),
        )
    }

    companion object {
        const val MIN_RATING = 1
        const val MAX_RATING = 5
        const val MAX_NOTE_LENGTH = 2000
        private val DATE_FORMAT_MESSAGE =
            UiMessage(R.string.validation_partial_day_format)
    }
}
