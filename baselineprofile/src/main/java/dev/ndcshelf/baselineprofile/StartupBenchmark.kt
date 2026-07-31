package dev.ndcshelf.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cold/warm startupのMacrobenchmark。
 *
 * docs/PERFORMANCE_BUDGETS.md の起動予算に対応する測定で、
 * flakyな時間指標のためCI必須ジョブには入れず、
 * `.github/workflows/benchmark.yml`（workflow_dispatch）と
 * 実機ラボ（リリースチェックリスト）で実行して記録する。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutBaselineProfile() = startup(StartupMode.COLD, CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() =
        startup(
            StartupMode.COLD,
            CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        )

    @Test
    fun warmStartupWithBaselineProfile() =
        startup(
            StartupMode.WARM,
            CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        )

    private fun startup(
        startupMode: StartupMode,
        compilationMode: CompilationMode,
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = startupMode,
        compilationMode = compilationMode,
    ) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        const val TARGET_PACKAGE = "dev.ndcshelf.app"
    }
}
