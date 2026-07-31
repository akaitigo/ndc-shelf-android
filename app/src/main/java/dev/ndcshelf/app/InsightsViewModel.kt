package dev.ndcshelf.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.ndcshelf.app.domain.insights.InsightsExclusionStore
import dev.ndcshelf.app.domain.insights.InsightsMonth
import dev.ndcshelf.app.domain.insights.LibraryInsights
import dev.ndcshelf.app.domain.insights.LibraryInsightsCalculator
import dev.ndcshelf.app.domain.model.LibraryBook
import dev.ndcshelf.app.domain.repository.LibraryRepository
import dev.ndcshelf.app.domain.repository.ReadingHistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import kotlin.random.Random

/**
 * 分析（Insights）画面のViewModel。蔵書・読書履歴・除外リストを購読し、
 * 端末内だけで傾向と再発見候補を集計する（外部送信なし。docs/INSIGHTS.md参照）。
 *
 * 再発見候補のseedは画面のViewModel生存期間中は固定し、
 * 表示のたびに候補が入れ替わって操作対象を見失うことを防ぐ。
 */
class InsightsViewModel(
    libraryRepository: LibraryRepository,
    readingHistoryRepository: ReadingHistoryRepository,
    private val exclusionStore: InsightsExclusionStore,
    private val calculator: LibraryInsightsCalculator = LibraryInsightsCalculator(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    rediscoverySeed: Long = Random.Default.nextLong(),
) : ViewModel() {
    private val seed = rediscoverySeed

    val state: StateFlow<InsightsUiState> =
        combine(
            libraryRepository.observeLibrary(),
            readingHistoryRepository.observeAllSessions(),
            exclusionStore.observeExcludedCopyIds(),
        ) { books, sessions, excludedCopyIds ->
            val now = nowMillis()
            InsightsUiState.Ready(
                books = books,
                insights =
                    calculator.calculate(
                        books = books,
                        sessions = sessions,
                        excludedCopyIds = excludedCopyIds,
                        nowMillis = now,
                        currentMonth = currentMonthOf(now),
                        rediscoverySeed = seed,
                    ),
            ) as InsightsUiState
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InsightsUiState.Loading,
        )

    fun excludeBook(copyId: String) {
        exclusionStore.exclude(copyId)
    }

    fun resetExclusions() {
        exclusionStore.clear()
    }

    private fun currentMonthOf(millis: Long): InsightsMonth {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return InsightsMonth(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
        )
    }

    companion object {
        fun factory(
            libraryRepository: LibraryRepository,
            readingHistoryRepository: ReadingHistoryRepository,
            exclusionStore: InsightsExclusionStore,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    InsightsViewModel(
                        libraryRepository = libraryRepository,
                        readingHistoryRepository = readingHistoryRepository,
                        exclusionStore = exclusionStore,
                    )
                }
            }
    }
}

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Ready(
        val books: List<LibraryBook>,
        val insights: LibraryInsights,
    ) : InsightsUiState
}
