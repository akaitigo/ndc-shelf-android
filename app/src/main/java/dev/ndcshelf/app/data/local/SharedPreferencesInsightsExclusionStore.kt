package dev.ndcshelf.app.data.local

import android.content.Context
import androidx.core.content.edit
import dev.ndcshelf.app.domain.insights.InsightsExclusionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分析画面の除外リストをアプリ専用SharedPreferencesへ保存する。
 *
 * Roomのschema変更（migration）を伴わない表示設定として保持し、
 * バックアップ・同期・エクスポートの対象へ含めない。肥大化を防ぐため
 * 上限件数を超えた追加は受け付けない（分析リセットで全消去できる）。
 */
class SharedPreferencesInsightsExclusionStore(
    context: Context,
) : InsightsExclusionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val excluded = MutableStateFlow(load())

    override fun observeExcludedCopyIds(): Flow<Set<String>> = excluded.asStateFlow()

    override fun exclude(copyId: String) {
        val current = excluded.value
        if (copyId in current || current.size >= MAX_ENTRIES) return
        val next = current + copyId
        preferences.edit { putStringSet(KEY_EXCLUDED_COPY_IDS, next) }
        excluded.value = next
    }

    override fun clear() {
        preferences.edit { remove(KEY_EXCLUDED_COPY_IDS) }
        excluded.value = emptySet()
    }

    private fun load(): Set<String> = preferences.getStringSet(KEY_EXCLUDED_COPY_IDS, emptySet()).orEmpty().toSet()

    companion object {
        const val MAX_ENTRIES = 1000
        private const val PREFERENCES_NAME = "insights-exclusions"
        private const val KEY_EXCLUDED_COPY_IDS = "excluded-copy-ids"
    }
}
