package dev.ndcshelf.app.domain.ai.llm

import dev.ndcshelf.app.domain.ai.AiLibrarianAnswer
import dev.ndcshelf.app.domain.ai.AiLibrarianProvider
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderErrorKind
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderException
import dev.ndcshelf.app.domain.ai.AiLibrarianProviderId
import dev.ndcshelf.app.domain.ai.AiLibrarianRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 端末内LLMのAI司書プロバイダ（設計判断はdocs/adr/0009-on-device-llm-librarian.md）。
 *
 * 不変条件:
 * - ネットワークAPIを使用しない。モデル取得は[LlmModelStore]の別経路に限る。
 * - 推論をmain threadで実行しない（[dispatcher]は既定でDispatchers.Default）。
 * - 端末能力・モデル状態を毎回検査し、条件を満たさなければ推論を始めない。
 * - モデルはプロセスごとに1度、全バイトのSHA-256を再照合してからロードする。
 * - 出力は[LlmAnswerParser]の検証を通ったものだけを返す。未検証の部分回答を返さない。
 * - 診断へはmodel version/runtime/hash先頭/所要時間/失敗分類だけを記録する。
 */
class OnDeviceLlmLibrarian(
    private val capabilityProvider: () -> LlmCapability,
    private val modelStore: LlmModelStore,
    private val runtime: LlmInferenceRuntime,
    private val runtimeCacheDir: java.io.File,
    private val telemetry: LlmTelemetrySink = NoOpLlmTelemetrySink,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AiLibrarianProvider {
    override val id: AiLibrarianProviderId = AiLibrarianProviderId.ON_DEVICE_LLM

    override val sendsDataOffDevice: Boolean = false

    /** プロセス内で整合性を再確認済みのモデル（"id@version"）。 */
    private val verifiedModels = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    override suspend fun answer(request: AiLibrarianRequest): AiLibrarianAnswer {
        val capability = capabilityProvider()
        if (capability !is LlmCapability.Supported) {
            throw AiLibrarianProviderException(AiLibrarianProviderErrorKind.UNAVAILABLE)
        }
        if (request.items.isEmpty()) {
            throw AiLibrarianProviderException(AiLibrarianProviderErrorKind.INVALID_RESPONSE)
        }
        val model = capability.model
        val modelFile =
            modelStore.installedFile(model)
                ?: throw failAndReport(model, LlmFailureKind.MODEL_MISSING, AiLibrarianProviderErrorKind.UNAVAILABLE)

        val prompt =
            runCatching { LlmPromptBuilder.build(request) }.getOrNull()
                ?: throw AiLibrarianProviderException(AiLibrarianProviderErrorKind.INVALID_RESPONSE)

        return withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            val initStart = nowMillis()
            // 導入後にファイルが差し替えられていないことを、プロセスごとに1度だけ全バイトで確認する。
            val modelKey = "${model.id}@${model.version}"
            if (modelKey !in verifiedModels) {
                if (!modelStore.verifyInstalled(model)) {
                    throw failAndReport(
                        model,
                        LlmFailureKind.MODEL_CORRUPTED,
                        AiLibrarianProviderErrorKind.UNAVAILABLE,
                        nowMillis() - initStart,
                    )
                }
                verifiedModels += modelKey
            }
            // 推論runtimeは重み最適化キャッシュをここへ書く。存在しないと
            // 毎回キャッシュを作れず初期化が遅くなるため、開く前に必ず用意する。
            runCatching { runtimeCacheDir.mkdirs() }
            val session =
                try {
                    runtime.open(
                        LlmLoadRequest(
                            model = model,
                            modelFile = modelFile,
                            maxTokens = model.contextTokens,
                            cacheDir = runtimeCacheDir,
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (runtimeError: LlmRuntimeException) {
                    throw failAndReport(model, runtimeError.kind, runtimeError.kind.toProviderErrorKind())
                } catch (_: Exception) {
                    throw failAndReport(
                        model,
                        LlmFailureKind.INITIALIZATION_FAILED,
                        AiLibrarianProviderErrorKind.UNAVAILABLE,
                    )
                }
            val initializationMillis = nowMillis() - initStart

            session.use {
                val inferenceStart = nowMillis()
                val raw =
                    try {
                        session.generate(prompt, LlmPromptLimits.MAX_OUTPUT_TOKENS)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (runtimeError: LlmRuntimeException) {
                        throw failAndReport(
                            model,
                            runtimeError.kind,
                            runtimeError.kind.toProviderErrorKind(),
                            initializationMillis,
                            nowMillis() - inferenceStart,
                        )
                    } catch (_: Exception) {
                        throw failAndReport(
                            model,
                            LlmFailureKind.INFERENCE_FAILED,
                            AiLibrarianProviderErrorKind.UNAVAILABLE,
                            initializationMillis,
                            nowMillis() - inferenceStart,
                        )
                    }
                val inferenceMillis = nowMillis() - inferenceStart
                when (val parsed = LlmAnswerParser.parse(raw, prompt.allowedRefs)) {
                    is LlmAnswerParseResult.Valid -> {
                        report(
                            model = model,
                            failure = null,
                            initializationMillis = initializationMillis,
                            inferenceMillis = inferenceMillis,
                            promptChars = prompt.text.length,
                            outputChars = raw.length,
                        )
                        parsed.answer
                    }

                    LlmAnswerParseResult.Invalid -> {
                        throw failAndReport(
                            model,
                            LlmFailureKind.INVALID_OUTPUT,
                            AiLibrarianProviderErrorKind.INVALID_RESPONSE,
                            initializationMillis,
                            inferenceMillis,
                            prompt.text.length,
                            raw.length,
                        )
                    }
                }
            }
        }
    }

    private fun failAndReport(
        model: LlmModelDefinition,
        kind: LlmFailureKind,
        providerKind: AiLibrarianProviderErrorKind,
        initializationMillis: Long = 0L,
        inferenceMillis: Long = 0L,
        promptChars: Int = 0,
        outputChars: Int = 0,
    ): AiLibrarianProviderException {
        report(model, kind, initializationMillis, inferenceMillis, promptChars, outputChars)
        return AiLibrarianProviderException(providerKind)
    }

    private fun report(
        model: LlmModelDefinition,
        failure: LlmFailureKind?,
        initializationMillis: Long,
        inferenceMillis: Long,
        promptChars: Int,
        outputChars: Int,
    ) {
        runCatching {
            telemetry.record(
                LlmInferenceTelemetry(
                    modelId = model.id,
                    modelVersion = model.version,
                    modelHashPrefix = model.sha256.take(MODEL_HASH_PREFIX_LENGTH),
                    runtime = runtime.runtimeId,
                    runtimeVersion = runtime.runtimeVersion,
                    initializationMillis = initializationMillis,
                    inferenceMillis = inferenceMillis,
                    promptChars = promptChars,
                    outputChars = outputChars,
                    failure = failure,
                ),
            )
        }
    }

    private fun LlmFailureKind.toProviderErrorKind(): AiLibrarianProviderErrorKind =
        when (this) {
            LlmFailureKind.INVALID_OUTPUT -> AiLibrarianProviderErrorKind.INVALID_RESPONSE

            LlmFailureKind.DEVICE_UNSUPPORTED,
            LlmFailureKind.MODEL_MISSING,
            LlmFailureKind.MODEL_CORRUPTED,
            LlmFailureKind.INITIALIZATION_FAILED,
            LlmFailureKind.INFERENCE_FAILED,
            -> AiLibrarianProviderErrorKind.UNAVAILABLE
        }

    private companion object {
        const val MODEL_HASH_PREFIX_LENGTH = 16
    }
}
