package dev.ndcshelf.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 起動経路のBaseline Profileを収集する。
 *
 * 実行はGradle Managed Device（pixel7Api35）越しの
 * `:app:generateReleaseBaselineProfile` を想定し、CIでは
 * `.github/workflows/benchmark.yml` の workflow_dispatch からだけ動かす。
 * 収集対象はR8有効のnonMinifiedRelease（署名はdebug署名）で、
 * 個人蔵書データには一切依存しない（初回起動状態の画面遷移のみ）。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }

    private companion object {
        const val TARGET_PACKAGE = "dev.ndcshelf.app"
    }
}
