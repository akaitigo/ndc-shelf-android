package dev.ndcshelf.app.domain.insights

import dev.ndcshelf.app.domain.model.ClassificationSource
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.MediaType
import dev.ndcshelf.app.domain.model.PartialDate
import dev.ndcshelf.app.domain.model.ReadingSession
import dev.ndcshelf.app.domain.model.ReadingSessionStatus
import dev.ndcshelf.app.domain.model.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryInsightsCalculatorTest {
    private val calculator = LibraryInsightsCalculator()
    private val now = 1_753_000_000_000L
    private val currentMonth = InsightsMonth(2026, 7)

    // --- 積読（時刻境界・インポート直後・除外） ---

    @Test
    fun tsundokuOrdersUnreadByOldestAddedAtWithDayCounts() {
        val books =
            listOf(
                book("copy-new", addedAt = now - days(1)),
                book("copy-old", addedAt = now - days(420)),
                book("copy-read", addedAt = now - days(900), readingStatus = ReadingStatus.READ),
            )

        val insights = calculate(books = books)

        assertEquals(2, insights.tsundoku.unreadCount)
        assertEquals(
            listOf("copy-old" to 420L, "copy-new" to 1L),
            insights.tsundoku.longestUnread.map { it.book.copyId to it.daysSinceAdded },
        )
    }

    @Test
    fun tsundokuDayBoundaryRoundsDownAndNeverGoesNegative() {
        val books =
            listOf(
                book("copy-same-moment", addedAt = now),
                book("copy-almost-one-day", addedAt = now - days(1) + 1),
                book("copy-exactly-one-day", addedAt = now - days(1)),
                book("copy-clock-skew", addedAt = now + days(3)),
            )

        val byId =
            calculate(books = books)
                .tsundoku.longestUnread
                .associate { it.book.copyId to it.daysSinceAdded }

        assertEquals(0L, byId.getValue("copy-same-moment"))
        assertEquals(0L, byId.getValue("copy-almost-one-day"))
        assertEquals(1L, byId.getValue("copy-exactly-one-day"))
        assertEquals(0L, byId.getValue("copy-clock-skew"))
    }

    @Test
    fun tsundokuIsStableWhenImportAddsBooksAtTheSameInstant() {
        val importedAt = now - days(30)
        val books = listOf("copy-c", "copy-a", "copy-b").map { book(it, addedAt = importedAt) }

        val first = calculate(books = books).tsundoku.longestUnread.map { it.book.copyId }
        val second = calculate(books = books.reversed()).tsundoku.longestUnread.map { it.book.copyId }

        assertEquals(listOf("copy-a", "copy-b", "copy-c"), first)
        assertEquals(first, second)
    }

    @Test
    fun excludedCopiesLeaveTsundokuAndRediscoveryButNotCounts() {
        val books =
            listOf(
                book("copy-1", addedAt = now - days(100)),
                book("copy-2", addedAt = now - days(50)),
            )

        val insights = calculate(books = books, excludedCopyIds = setOf("copy-1", "copy-deleted"))

        assertEquals(listOf("copy-2"), insights.tsundoku.longestUnread.map { it.book.copyId })
        assertEquals(listOf("copy-2"), insights.rediscoveries.map { it.book.copyId })
        // 集計値は偽らない: 総数・未読数は全数のまま、除外数は現存する本だけ数える
        assertEquals(2, insights.totalCount)
        assertEquals(2, insights.tsundoku.unreadCount)
        assertEquals(1, insights.excludedCount)
    }

    // --- 読了推移（部分日付・年跨ぎ・重複・削除） ---

    @Test
    fun trendBelowThresholdExplainsRequiredHistoryInsteadOfEmptyChart() {
        val sessions =
            listOf(
                finished("session-1", finishedDay = "2026-07-01"),
                finished("session-2", finishedDay = "2026"),
                finished("session-3", finishedDay = null),
            )

        val trend = calculate(sessions = sessions).finishedTrend

        // 月精度以上の読了日を持つのは1件だけなので、断定せず必要データを説明する
        assertEquals(
            FinishedTrendInsight.InsufficientHistory(datedSessionCount = 1, requiredSessionCount = 3),
            trend,
        )
    }

    @Test
    fun trendBucketsMonthsAcrossYearBoundary() {
        val sessions =
            listOf(
                finished("session-1", finishedDay = "2025-12-31"),
                finished("session-2", finishedDay = "2026-01"),
                finished("session-3", finishedDay = "2026-01-01"),
                finished("session-4", finishedDay = "2026-07-15"),
            )

        val trend = calculate(sessions = sessions).finishedTrend as FinishedTrendInsight.Ready

        assertEquals(InsightsMonth(2025, 8), trend.monthlyCounts.first().month)
        assertEquals(InsightsMonth(2026, 7), trend.monthlyCounts.last().month)
        assertEquals(12, trend.monthlyCounts.size)
        val byMonth = trend.monthlyCounts.associate { it.month to it.count }
        assertEquals(1, byMonth.getValue(InsightsMonth(2025, 12)))
        assertEquals(2, byMonth.getValue(InsightsMonth(2026, 1)))
        assertEquals(1, byMonth.getValue(InsightsMonth(2026, 7)))
        assertEquals(0, byMonth.getValue(InsightsMonth(2026, 6)))
        assertEquals(0, trend.outsideWindowCount)
    }

    @Test
    fun trendSeparatesYearOnlyUndatedAndOutsideWindowSessions() {
        val sessions =
            listOf(
                finished("session-1", finishedDay = "2026-07-01"),
                finished("session-2", finishedDay = "2026-06"),
                finished("session-3", finishedDay = "2026-05"),
                finished("session-4", finishedDay = "2026"),
                finished("session-5", finishedDay = null),
                finished("session-6", finishedDay = "2020-01-01"),
                finished("session-7", finishedDay = "2027-01"),
            )

        val trend = calculate(sessions = sessions).finishedTrend as FinishedTrendInsight.Ready

        assertEquals(1, trend.yearOnlyCount)
        assertEquals(1, trend.undatedCount)
        assertEquals(2, trend.outsideWindowCount)
        assertEquals(3, trend.monthlyCounts.sumOf(FinishedTrendPoint::count))
    }

    @Test
    fun duplicatedSessionIdsFromSyncAreCountedOnce() {
        val original = finished("session-1", finishedDay = "2026-07-01")
        val syncDuplicate = original.copy(updatedAt = original.updatedAt + 10)
        val sessions =
            listOf(
                original,
                syncDuplicate,
                finished("session-2", finishedDay = "2026-06-01"),
                finished("session-3", finishedDay = "2026-05-01"),
            )

        val trend = calculate(sessions = sessions).finishedTrend as FinishedTrendInsight.Ready

        assertEquals(3, trend.monthlyCounts.sumOf(FinishedTrendPoint::count))
        assertEquals(
            1,
            trend.monthlyCounts.first { it.month == InsightsMonth(2026, 7) }.count,
        )
    }

    @Test
    fun nonFinishedSessionsNeverEnterTheTrend() {
        val sessions =
            listOf(
                finished("session-1", finishedDay = "2026-07-01"),
                finished("session-2", finishedDay = "2026-06-01"),
                finished("session-3", finishedDay = "2026-05-01"),
                session("session-4", status = ReadingSessionStatus.READING),
                session("session-5", status = ReadingSessionStatus.PAUSED),
            )

        val trend = calculate(sessions = sessions).finishedTrend as FinishedTrendInsight.Ready

        assertEquals(3, trend.monthlyCounts.sumOf(FinishedTrendPoint::count))
    }

    @Test
    fun deletedBooksNeverAppearAsCandidatesEvenIfSessionsRemainInInput() {
        val books = listOf(book("copy-alive", addedAt = now - days(10)))
        val sessions =
            listOf(
                finished("session-1", copyId = "copy-deleted", finishedDay = "2026-07-01"),
                finished("session-2", copyId = "copy-deleted", finishedDay = "2026-06-01"),
                finished("session-3", copyId = "copy-deleted", finishedDay = "2026-05-01"),
            )

        val insights = calculate(books = books, sessions = sessions)

        // 候補は現在の蔵書だけから選ぶ
        assertEquals(listOf("copy-alive"), insights.tsundoku.longestUnread.map { it.book.copyId })
        assertEquals(listOf("copy-alive"), insights.rediscoveries.map { it.book.copyId })
        // 渡された履歴自体は推移として数える（通常はコピー削除と同時に履歴も消える）
        val trend = insights.finishedTrend as FinishedTrendInsight.Ready
        assertEquals(3, trend.monthlyCounts.sumOf(FinishedTrendPoint::count))
    }

    // --- ランダム再発見（決定性・理由） ---

    @Test
    fun rediscoveryIsDeterministicForTheSameSeed() {
        val books = (1..20).map { book("copy-%02d".format(it), addedAt = now - days(it.toLong())) }

        val first = calculate(books = books, seed = 42L).rediscoveries.map { it.book.copyId }
        val second = calculate(books = books.shuffled(), seed = 42L).rediscoveries.map { it.book.copyId }

        assertEquals(first, second)
        assertEquals(3, first.size)
        assertEquals(first.size, first.distinct().size)
    }

    @Test
    fun rediscoveryReasonsExplainWhyEachBookWasChosen() {
        val books =
            listOf(
                book("copy-unread", addedAt = now - days(420)),
                book("copy-paused", readingStatus = ReadingStatus.PAUSED),
                book("copy-finished", readingStatus = ReadingStatus.READ),
            )
        val sessions =
            listOf(
                finished("session-old", copyId = "copy-finished", finishedDay = "2024-01-01", createdAt = 1L),
                finished("session-new", copyId = "copy-finished", finishedDay = "2025-03", createdAt = 2L),
            )

        val reasons =
            calculate(books = books, sessions = sessions)
                .rediscoveries
                .associate { it.book.copyId to it.reason }

        assertEquals(RediscoveryReason.UnreadSinceAdded(420L), reasons.getValue("copy-unread"))
        assertEquals(RediscoveryReason.PausedMidway, reasons.getValue("copy-paused"))
        assertEquals(
            RediscoveryReason.FinishedBefore(PartialDate.parse("2025-03")),
            reasons.getValue("copy-finished"),
        )
    }

    @Test
    fun rediscoveryOfFinishedBookWithoutDatedSessionOmitsDate() {
        val books = listOf(book("copy-finished", readingStatus = ReadingStatus.READ))

        val reasons = calculate(books = books).rediscoveries.associate { it.book.copyId to it.reason }

        assertEquals(RediscoveryReason.FinishedBefore(null), reasons.getValue("copy-finished"))
    }

    // --- NDC分布 ---

    @Test
    fun ndcDistributionUsesFirstDigitWithRatiosOfClassifiedBooks() {
        val books =
            listOf(
                book("copy-1", ndcCode = "913.6"),
                book("copy-2", ndcCode = "914"),
                book("copy-3", ndcCode = "420"),
                book("copy-4", ndcCode = null),
            )

        val insights = calculate(books = books)

        assertEquals(
            listOf(
                NdcShare(digit = 4, label = "自然科学", count = 1, ratio = 1f / 3),
                NdcShare(digit = 9, label = "文学", count = 2, ratio = 2f / 3),
            ),
            insights.ndcDistribution,
        )
        assertEquals(1, insights.unclassifiedCount)
    }

    // --- InsightsMonth ---

    @Test
    fun insightsMonthArithmeticCrossesYearBoundaries() {
        assertEquals(InsightsMonth(2025, 8), InsightsMonth(2026, 7).minusMonths(11))
        assertEquals(InsightsMonth(2025, 12), InsightsMonth(2026, 1).minusMonths(1))
        assertEquals(InsightsMonth(2024, 12), InsightsMonth(2026, 1).minusMonths(13))
        assertTrue(InsightsMonth(2025, 12) < InsightsMonth(2026, 1))
        assertEquals("2026-07", InsightsMonth(2026, 7).format())
    }

    // --- helpers ---

    private fun calculate(
        books: List<LibraryBook> = emptyList(),
        sessions: List<ReadingSession> = emptyList(),
        excludedCopyIds: Set<String> = emptySet(),
        seed: Long = 0L,
    ): LibraryInsights =
        calculator.calculate(
            books = books,
            sessions = sessions,
            excludedCopyIds = excludedCopyIds,
            nowMillis = now,
            currentMonth = currentMonth,
            rediscoverySeed = seed,
        )

    private fun days(count: Long): Long = count * 24 * 60 * 60 * 1000

    private fun book(
        copyId: String,
        readingStatus: ReadingStatus = ReadingStatus.UNREAD,
        addedAt: Long = 0L,
        ndcCode: String? = null,
    ): LibraryBook =
        LibraryBook(
            copyId = copyId,
            workId = "work-$copyId",
            editionId = "edition-$copyId",
            title = "タイトル$copyId",
            primaryAuthor = "著者",
            isbn13 = null,
            publisher = null,
            publishedYear = null,
            coverUrl = null,
            ndcCode = ndcCode,
            ndcEdition = null,
            classificationSource = ClassificationSource.MANUAL,
            mediaType = MediaType.PHYSICAL,
            location = "本棚",
            readingStatus = readingStatus,
            addedAt = addedAt,
        )

    private fun finished(
        id: String,
        copyId: String = "copy-1",
        finishedDay: String?,
        createdAt: Long = 0L,
    ): ReadingSession =
        session(
            id = id,
            copyId = copyId,
            status = ReadingSessionStatus.FINISHED,
            finishedDay = finishedDay,
            createdAt = createdAt,
        )

    private fun session(
        id: String,
        copyId: String = "copy-1",
        status: ReadingSessionStatus,
        finishedDay: String? = null,
        createdAt: Long = 0L,
    ): ReadingSession =
        ReadingSession(
            id = id,
            copyId = copyId,
            copyLabel = "所蔵本",
            status = status,
            startedDay = null,
            finishedDay = finishedDay?.let { requireNotNull(PartialDate.parse(it)) },
            rating = null,
            note = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}
