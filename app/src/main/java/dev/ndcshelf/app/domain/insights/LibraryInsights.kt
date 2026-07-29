package dev.ndcshelf.app.domain.insights

import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.model.PartialDate

/**
 * 端末内の蔵書・読書履歴から導出する読書傾向と再発見候補。
 *
 * 指標の定義・データ源・限界は docs/INSIGHTS.md を正本とする。
 * 集計は端末内でのみ行い、外部送信しない。
 */
data class LibraryInsights(
    val totalCount: Int,
    val readingCount: Int,
    val finishedCount: Int,
    val ndcDistribution: List<NdcShare>,
    val unclassifiedCount: Int,
    val tsundoku: TsundokuInsight,
    val finishedTrend: FinishedTrendInsight,
    val rediscoveries: List<RediscoveryCandidate>,
    /** 現在の蔵書のうち「対象外にする」で除外されている冊数。 */
    val excludedCount: Int,
)

/** NDC類（第1桁）ごとの冊数と全分類済み冊数に対する比率。 */
data class NdcShare(
    val digit: Int,
    val label: String,
    val count: Int,
    val ratio: Float,
)

/**
 * 積読の指標。未読（UNREAD）の所有コピーについて、addedAtからの経過日数を示す。
 * 経過日数は86,400秒を1日とした端末時刻ベースの近似値で、暦日の境界は考慮しない。
 */
data class TsundokuInsight(
    val unreadCount: Int,
    val longestUnread: List<TsundokuCandidate>,
)

data class TsundokuCandidate(
    val book: LibraryBook,
    val daysSinceAdded: Long,
)

/** 年月（暦月）。読了推移のバケットキーとして使う。 */
data class InsightsMonth(
    val year: Int,
    val month: Int,
) : Comparable<InsightsMonth> {
    init {
        require(month in 1..12)
    }

    override fun compareTo(other: InsightsMonth): Int = compareValuesBy(this, other, InsightsMonth::year, InsightsMonth::month)

    fun minusMonths(count: Int): InsightsMonth {
        require(count >= 0)
        val total = year * 12 + (month - 1) - count
        return InsightsMonth(year = total / 12, month = total % 12 + 1)
    }

    fun format(): String = "%04d-%02d".format(year, month)
}

/**
 * 読了推移。読了（FINISHED）セッションの読了日（finishedDay）だけをデータ源とする。
 * 月精度未満（年のみ）や読了日なしのセッションは月別グラフへ含めず、件数として別掲する。
 */
sealed interface FinishedTrendInsight {
    /** 月精度以上の読了日を持つセッションが閾値未満。グラフを表示せず必要データを説明する。 */
    data class InsufficientHistory(
        val datedSessionCount: Int,
        val requiredSessionCount: Int,
    ) : FinishedTrendInsight

    data class Ready(
        /** 直近の表示期間（古い月から新しい月の順、欠けた月は0冊で埋める）。 */
        val monthlyCounts: List<FinishedTrendPoint>,
        /** 読了日が年のみでどの月にも割り当てられない件数。 */
        val yearOnlyCount: Int,
        /** 読了日のない読了セッション件数。 */
        val undatedCount: Int,
        /** 表示期間より前（または未来）の読了件数。 */
        val outsideWindowCount: Int,
    ) : FinishedTrendInsight
}

data class FinishedTrendPoint(
    val month: InsightsMonth,
    val count: Int,
)

/** ランダム再発見の候補。必ず「選ばれた理由」を添える。 */
data class RediscoveryCandidate(
    val book: LibraryBook,
    val reason: RediscoveryReason,
)

/**
 * 再発見候補の理由。表示文言は評価や催促を含めず、事実の説明に限る
 * （docs/INSIGHTS.md の表現ガイドライン参照）。
 */
sealed interface RediscoveryReason {
    /** 追加からの経過日数つきの未読。 */
    data class UnreadSinceAdded(
        val daysSinceAdded: Long,
    ) : RediscoveryReason

    /** 中断中（読みかけ）の本。 */
    data object PausedMidway : RediscoveryReason

    /** 読了済みの再読候補。読了日が分かる場合だけ添える。 */
    data class FinishedBefore(
        val finishedDay: PartialDate?,
    ) : RediscoveryReason
}
