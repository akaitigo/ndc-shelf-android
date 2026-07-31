package dev.ndcshelf.app.domain.insights

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分析画面の「対象外にする」「再表示しない」で除外したコピーIDの保存契約。
 *
 * 除外は候補提示だけに影響する端末内の表示設定であり、蔵書データ・読書履歴を
 * 変更しない。同期・エクスポート・バックアップの対象にも含めない。
 * 「分析リセット」は除外リストを全消去して全ての本を候補へ戻す。
 */
interface InsightsExclusionStore {
    fun observeExcludedCopyIds(): Flow<Set<String>>

    fun exclude(copyId: String)

    /** 分析リセット: 除外リストを全消去する。 */
    fun clear()
}

/** テスト・プレビュー用のインメモリ実装。 */
class InMemoryInsightsExclusionStore(
    initial: Set<String> = emptySet(),
) : InsightsExclusionStore {
    private val excluded = MutableStateFlow(initial)

    override fun observeExcludedCopyIds(): Flow<Set<String>> = excluded.asStateFlow()

    override fun exclude(copyId: String) {
        excluded.value = excluded.value + copyId
    }

    override fun clear() {
        excluded.value = emptySet()
    }
}
