package dev.ndcshelf.app.data.llm

import dev.ndcshelf.app.domain.ai.llm.LlmInferenceRuntime
import dev.ndcshelf.app.domain.ai.llm.UnavailableLlmRuntime

/**
 * standardフレーバーの推論runtime。
 *
 * 配布物へLLMのnative libraryを一切含めないため、常に利用不可を返す。
 * [dev.ndcshelf.app.domain.ai.llm.LlmCapabilityChecker]がこの値を見て
 * `RUNTIME_UNAVAILABLE`で取得も起動も抑止する（fail-closed）。
 */
object PlatformLlmRuntime {
    val runtime: LlmInferenceRuntime = UnavailableLlmRuntime

    /** standardフレーバーでは常にfalse。 */
    fun isAvailable(): Boolean = false
}
