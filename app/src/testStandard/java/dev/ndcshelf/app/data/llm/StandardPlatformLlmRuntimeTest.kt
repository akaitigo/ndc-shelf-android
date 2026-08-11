package dev.ndcshelf.app.data.llm

import dev.ndcshelf.app.domain.ai.llm.LlmCapability
import dev.ndcshelf.app.domain.ai.llm.LlmCapabilityChecker
import dev.ndcshelf.app.domain.ai.llm.LlmUnsupportedReason
import dev.ndcshelf.app.domain.ai.llm.UnavailableLlmRuntime
import dev.ndcshelf.app.domain.ai.llm.supportedProfile
import dev.ndcshelf.app.domain.ai.llm.testModelDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配布物（`standard`）が端末内LLMを一切起動しないことの回帰テスト。
 *
 * ADR 0009により`ai`フレーバーはGitHub Releasesへ添付せず、利用者が受け取るのは
 * `standard`だけである。`standard`にはLiteRT-LMのネイティブライブラリを含めないため、
 * 「推論runtimeが常に利用不可」という前提が崩れると、存在しないエンジンを前提に
 * 475MBのモデル取得導線を出してしまう。
 *
 * `docs/releases/V1.0_RELEASE_CHECKLIST.md` の受け入れ条件4-1（`standard`）に対応する。
 * このテストは`testStandardDebugUnitTest`でだけ意味を持つため、共通の`test`ではなく
 * flavor固有の`testStandard`へ置く（`ai`側では`isAvailable()`がtrueになりうる）。
 */
class StandardPlatformLlmRuntimeTest {
    @Test
    fun `standard build never reports an available inference runtime`() {
        assertFalse(PlatformLlmRuntime.isAvailable())
    }

    @Test
    fun `standard build wires the fail-closed runtime`() {
        assertSame(UnavailableLlmRuntime, PlatformLlmRuntime.runtime)
    }

    @Test
    fun `otherwise capable device is still unsupported because the engine is absent`() {
        // 端末条件・モデル・機能flagを全て満たしても、runtimeが無い一点で拒否されること。
        // UIはRUNTIME_UNAVAILABLEをllm_model_unsupported_runtime
        // （「このビルドには推論エンジンが含まれていません」）へ対応付ける。
        val capability =
            LlmCapabilityChecker.evaluate(
                supportedProfile(runtimeAvailable = PlatformLlmRuntime.isAvailable()),
                testModelDefinition(),
                enabled = true,
            )

        assertTrue(capability is LlmCapability.Unsupported)
        val unsupported = capability as LlmCapability.Unsupported
        assertTrue(LlmUnsupportedReason.RUNTIME_UNAVAILABLE in unsupported.reasons)
    }

    @Test
    fun `model download is never offered in the standard build`() {
        // 取得導線を出さないこと。取得後に起動できない端末へ数百MBを落とさせない。
        assertFalse(
            LlmCapabilityChecker.canAcquire(
                supportedProfile(runtimeAvailable = PlatformLlmRuntime.isAvailable()),
                testModelDefinition(),
                enabled = true,
            ),
        )
    }
}
