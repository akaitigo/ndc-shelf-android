package dev.ndcshelf.app.domain.insights

import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.ReadingSession
import dev.ndcshelf.app.domain.model.ReadingSessionStatus
import dev.ndcshelf.app.domain.model.ReadingStatus
import kotlin.random.Random

/**
 * 読書傾向と再発見候補の集計。副作用を持たない純粋関数として実装し、
 * 現在時刻・現在月・乱数seedは引数で受け取る（docs/INSIGHTS.md参照）。
 *
 * 入力の扱い:
 * - `books` は現在の蔵書スナップショット。削除済みの本は含まれないため、
 *   積読・再発見の候補に削除済みの本が現れることはない。
 * - `sessions` は同期由来の重複を考慮し、同一セッションIDは1回だけ数える。
 * - `excludedCopyIds` の除外は積読・再発見の候補だけに適用し、
 *   冊数・NDC分布・読了推移の集計値からは除外しない（数を偽らない）。
 */
class LibraryInsightsCalculator(
    private val tsundokuLimit: Int = DEFAULT_TSUNDOKU_LIMIT,
    private val rediscoveryLimit: Int = DEFAULT_REDISCOVERY_LIMIT,
    private val trendWindowMonths: Int = DEFAULT_TREND_WINDOW_MONTHS,
    private val minimumTrendSessions: Int = DEFAULT_MINIMUM_TREND_SESSIONS,
) {
    init {
        require(tsundokuLimit >= 0)
        require(rediscoveryLimit >= 0)
        require(trendWindowMonths >= 1)
        require(minimumTrendSessions >= 1)
    }

    fun calculate(
        books: List<LibraryBook>,
        sessions: List<ReadingSession>,
        excludedCopyIds: Set<String>,
        nowMillis: Long,
        currentMonth: InsightsMonth,
        rediscoverySeed: Long,
    ): LibraryInsights {
        val uniqueSessions = sessions.distinctBy(ReadingSession::id)
        val eligibleBooks =
            books
                .filterNot { it.copyId in excludedCopyIds }
                .sortedBy(LibraryBook::copyId)
        return LibraryInsights(
            totalCount = books.size,
            readingCount = books.count { it.readingStatus == ReadingStatus.READING },
            finishedCount = books.count { it.readingStatus == ReadingStatus.READ },
            ndcDistribution = ndcDistribution(books),
            unclassifiedCount = books.count { it.ndcCategory == null },
            tsundoku = tsundoku(books, eligibleBooks, nowMillis),
            finishedTrend = finishedTrend(uniqueSessions, currentMonth),
            rediscoveries = rediscoveries(eligibleBooks, uniqueSessions, nowMillis, rediscoverySeed),
            excludedCount = books.count { it.copyId in excludedCopyIds },
        )
    }

    private fun ndcDistribution(books: List<LibraryBook>): List<NdcShare> {
        val categorized = books.mapNotNull(LibraryBook::ndcCategory)
        if (categorized.isEmpty()) return emptyList()
        val total = categorized.size
        return categorized
            .groupingBy { it }
            .eachCount()
            .map { (category, count) ->
                NdcShare(
                    digit = category.digit,
                    label = category.label,
                    count = count,
                    ratio = count.toFloat() / total,
                )
            }.sortedBy(NdcShare::digit)
    }

    /**
     * 積読: 未読（UNREAD）のコピーをaddedAtが古い順に並べる。
     * インポート直後などaddedAtが同一の場合はcopyIdで安定に並べ、
     * 経過日数は0日未満（時計の巻き戻り）を0日へ丸める。
     * 未読冊数は事実として全数を数え、除外は候補の提示だけに反映する。
     */
    private fun tsundoku(
        books: List<LibraryBook>,
        eligibleBooks: List<LibraryBook>,
        nowMillis: Long,
    ): TsundokuInsight {
        val longest =
            eligibleBooks
                .filter { it.readingStatus == ReadingStatus.UNREAD }
                .sortedWith(compareBy(LibraryBook::addedAt).thenBy(LibraryBook::copyId))
                .take(tsundokuLimit)
                .map { book ->
                    TsundokuCandidate(
                        book = book,
                        daysSinceAdded = daysSinceAdded(book, nowMillis),
                    )
                }
        return TsundokuInsight(
            unreadCount = books.count { it.readingStatus == ReadingStatus.UNREAD },
            longestUnread = longest,
        )
    }

    private fun finishedTrend(
        uniqueSessions: List<ReadingSession>,
        currentMonth: InsightsMonth,
    ): FinishedTrendInsight {
        val finished = uniqueSessions.filter { it.status == ReadingSessionStatus.FINISHED }
        val (dated, unassignable) = finished.partition { it.finishedDay?.month != null }
        if (dated.size < minimumTrendSessions) {
            return FinishedTrendInsight.InsufficientHistory(
                datedSessionCount = dated.size,
                requiredSessionCount = minimumTrendSessions,
            )
        }
        val windowStart = currentMonth.minusMonths(trendWindowMonths - 1)
        val counts = mutableMapOf<InsightsMonth, Int>()
        var outsideWindow = 0
        dated.forEach { session ->
            val day = requireNotNull(session.finishedDay)
            val month = InsightsMonth(day.year, requireNotNull(day.month))
            if (month in windowStart..currentMonth) {
                counts[month] = (counts[month] ?: 0) + 1
            } else {
                outsideWindow += 1
            }
        }
        val points =
            (0 until trendWindowMonths).map { offset ->
                val month = currentMonth.minusMonths(trendWindowMonths - 1 - offset)
                FinishedTrendPoint(month = month, count = counts[month] ?: 0)
            }
        return FinishedTrendInsight.Ready(
            monthlyCounts = points,
            yearOnlyCount = unassignable.count { it.finishedDay != null },
            undatedCount = unassignable.count { it.finishedDay == null },
            outsideWindowCount = outsideWindow,
        )
    }

    /**
     * ランダム再発見: 除外を反映した蔵書からseed付き乱数で無作為に選び、
     * 読書状態に応じた事実ベースの理由を添える。同じseedなら同じ候補を返す。
     */
    private fun rediscoveries(
        eligibleBooks: List<LibraryBook>,
        uniqueSessions: List<ReadingSession>,
        nowMillis: Long,
        rediscoverySeed: Long,
    ): List<RediscoveryCandidate> {
        val latestFinishedDayByCopy =
            uniqueSessions
                .filter { it.status == ReadingSessionStatus.FINISHED && it.finishedDay != null }
                .groupBy(ReadingSession::copyId)
                .mapValues { (_, copySessions) ->
                    copySessions.maxByOrNull(ReadingSession::createdAt)?.finishedDay
                }
        return eligibleBooks
            .shuffled(Random(rediscoverySeed))
            .take(rediscoveryLimit)
            .map { book ->
                RediscoveryCandidate(
                    book = book,
                    reason =
                        when (book.readingStatus) {
                            ReadingStatus.UNREAD -> {
                                RediscoveryReason.UnreadSinceAdded(daysSinceAdded(book, nowMillis))
                            }

                            ReadingStatus.READING, ReadingStatus.PAUSED -> {
                                RediscoveryReason.PausedMidway
                            }

                            ReadingStatus.READ -> {
                                RediscoveryReason.FinishedBefore(latestFinishedDayByCopy[book.copyId])
                            }
                        },
                )
            }
    }

    private fun daysSinceAdded(
        book: LibraryBook,
        nowMillis: Long,
    ): Long = ((nowMillis - book.addedAt) / MILLIS_PER_DAY).coerceAtLeast(0)

    companion object {
        const val DEFAULT_TSUNDOKU_LIMIT = 5
        const val DEFAULT_REDISCOVERY_LIMIT = 3
        const val DEFAULT_TREND_WINDOW_MONTHS = 12
        const val DEFAULT_MINIMUM_TREND_SESSIONS = 3
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
