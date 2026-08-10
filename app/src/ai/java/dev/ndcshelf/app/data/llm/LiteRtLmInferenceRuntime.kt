package dev.ndcshelf.app.data.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import dev.ndcshelf.app.domain.ai.llm.LlmFailureKind
import dev.ndcshelf.app.domain.ai.llm.LlmInferenceRuntime
import dev.ndcshelf.app.domain.ai.llm.LlmLoadRequest
import dev.ndcshelf.app.domain.ai.llm.LlmPrompt
import dev.ndcshelf.app.domain.ai.llm.LlmRuntimeException
import dev.ndcshelf.app.domain.ai.llm.LlmRuntimeId
import dev.ndcshelf.app.domain.ai.llm.LlmSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LiteRT-LM（`com.google.ai.edge.litertlm:litertlm-android` 0.15.0、Apache-2.0）の束縛。
 *
 * 不変条件:
 * - ネットワークAPIを一切使わない。読むのは検証済みのローカルモデルファイルだけ。
 * - 固定のsystem instructionは`ConversationConfig.systemInstruction`へ、書誌と質問文は
 *   userメッセージへ渡す。モデルのchat templateがroleを分離するため、書誌文字列が
 *   system roleへ混ざることがない。
 * - 例外は[LlmRuntimeException]へ分類し直し、原因例外を伝播させない。native側の
 *   メッセージへ入力が混ざっても、crash reportや診断ログへ流れない。
 * - キャンセル時は`cancelProcess()`でnative側の生成を打ち切る。
 * - サンプリングはgreedy（topK=1・temperature=0）に固定し、構造化JSONの揺らぎを抑える。
 */
class LiteRtLmInferenceRuntime : LlmInferenceRuntime {
    override val runtimeId: LlmRuntimeId = LlmRuntimeId.NATIVE

    override val runtimeVersion: String = RUNTIME_VERSION

    override suspend fun open(request: LlmLoadRequest): LlmSession {
        // nativeログへpromptが出ないよう、常にERRORまで抑制する。
        runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }

        val engine =
            try {
                Engine(
                    EngineConfig(
                        modelPath = request.modelFile.absolutePath,
                        backend = Backend.CPU(),
                        maxNumTokens = request.maxTokens,
                        cacheDir = request.cacheDir.absolutePath,
                    ),
                ).apply { initialize() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logFailure("engineInitialize", error)
                throw LlmRuntimeException(LlmFailureKind.INITIALIZATION_FAILED)
            }
        return LiteRtLmSession(engine)
    }

    private class LiteRtLmSession(
        private val engine: Engine,
    ) : LlmSession {
        private var conversation: Conversation? = null

        override suspend fun generate(
            prompt: LlmPrompt,
            maxOutputTokens: Int,
        ): String {
            val active =
                try {
                    engine
                        .createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(prompt.systemInstruction),
                                samplerConfig = GREEDY_SAMPLER,
                                maxOutputToken = maxOutputTokens,
                            ),
                        ).also { created -> conversation = created }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logFailure("createConversation", error)
                    throw LlmRuntimeException(LlmFailureKind.INITIALIZATION_FAILED)
                }

            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { active.cancelProcess() } }
                try {
                    val reply = active.sendMessage(prompt.userMessage)
                    val text =
                        reply.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString(separator = "") { content -> content.text }
                    if (continuation.isActive) continuation.resume(text)
                } catch (cancellation: CancellationException) {
                    if (continuation.isActive) continuation.resumeWithException(cancellation)
                } catch (error: Throwable) {
                    logFailure("sendMessage", error)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            LlmRuntimeException(LlmFailureKind.INFERENCE_FAILED),
                        )
                    }
                }
            }
        }

        override fun close() {
            runCatching { conversation?.close() }
            conversation = null
            runCatching { engine.close() }
        }
    }

    private companion object {
        const val RUNTIME_VERSION = "litert-lm/0.15.0"

        const val LOG_TAG = "NdcShelfLlm"

        /**
         * 失敗の切り分けに必要な最小限だけを記録する。
         *
         * 例外のメッセージには入力（質問文・書誌）が混ざりうるため出力しない。
         * 記録するのは例外のクラス名と、原因例外のクラス名、発生位置の先頭1フレームだけ。
         */
        fun logFailure(
            stage: String,
            error: Throwable,
        ) {
            val frame = error.stackTrace.firstOrNull()?.let { f -> "${f.className}.${f.methodName}" } ?: "unknown"
            val causes =
                generateSequence(error.cause) { c -> c.cause }
                    .take(3)
                    .joinToString(" <- ") { c -> c.javaClass.name }
            Log.e(LOG_TAG, "$stage failed: ${error.javaClass.name} at $frame causes=[$causes]")
        }

        /** greedy decoding。構造化JSONを返させるため揺らぎを最小にする。 */
        val GREEDY_SAMPLER = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
    }
}
