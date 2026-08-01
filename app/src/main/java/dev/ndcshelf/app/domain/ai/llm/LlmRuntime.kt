package dev.ndcshelf.app.domain.ai.llm

import java.io.File

/**
 * 推論runtimeの抽象。ネイティブ実装をこのinterfaceの背後へ隔離し、
 * JVMテストでは[dev.ndcshelf.app.domain.ai.llm.FakeLlmRuntime]で契約を検証する。
 *
 * 実装はネットワークAPIを使用してはならない。モデル取得は
 * [dev.ndcshelf.app.domain.ai.llm.LlmModelStore]だけが担う。
 */
interface LlmInferenceRuntime {
    val runtimeId: LlmRuntimeId

    /** 診断へ記録するruntime version。個人データを含めない。 */
    val runtimeVersion: String

    /**
     * モデルを読み込みsessionを開く。呼び出し側はmain threadで呼ばない。
     * 失敗は[LlmRuntimeException]で通知する。
     */
    suspend fun open(request: LlmLoadRequest): LlmSession
}

data class LlmLoadRequest(
    val model: LlmModelDefinition,
    val modelFile: File,
    val maxTokens: Int,
)

/** 1回の相談で使う推論session。使用後は必ず[close]する。 */
interface LlmSession : AutoCloseable {
    /**
     * [prompt]から応答文字列を生成する。cancelはcoroutineのキャンセルで行い、
     * 実装は速やかに中断すること。
     */
    suspend fun generate(
        prompt: String,
        maxOutputTokens: Int,
    ): String
}

/**
 * ネイティブ推論runtimeを同梱していないビルドの既定実装。
 *
 * 常に[LlmFailureKind.DEVICE_UNSUPPORTED]で失敗するため、能力判定を素通りしても
 * 推論が始まることはない（二重のfail-closed）。runtimeを採用したら差し替える。
 */
object UnavailableLlmRuntime : LlmInferenceRuntime {
    override val runtimeId: LlmRuntimeId = LlmRuntimeId.NATIVE

    override val runtimeVersion: String = "unavailable"

    override suspend fun open(request: LlmLoadRequest): LlmSession = throw LlmRuntimeException(LlmFailureKind.DEVICE_UNSUPPORTED)
}

/** runtime側の失敗種別。診断へはこのenum名だけを記録する。 */
enum class LlmFailureKind {
    /** 端末条件を満たさない。 */
    DEVICE_UNSUPPORTED,

    /** モデル未取得。 */
    MODEL_MISSING,

    /** checksum不一致・破損。 */
    MODEL_CORRUPTED,

    /** 初期化失敗（ネイティブ読み込み・session確保）。 */
    INITIALIZATION_FAILED,

    /** 推論中の失敗。 */
    INFERENCE_FAILED,

    /** 応答がschemaを満たさない。 */
    INVALID_OUTPUT,
}

class LlmRuntimeException(
    val kind: LlmFailureKind,
    override val message: String? = kind.name,
) : Exception(message)

/**
 * 推論1回分の診断記録。allowlist方式に従い、質問文・書誌・回答を持てない構造にする。
 * 値は台帳由来の固定文字列（model id/version、runtime version）と数値・enumだけ。
 */
data class LlmInferenceTelemetry(
    val modelId: String,
    val modelVersion: String,
    /** モデルファイルSHA-256の先頭16桁。完全なhashは端末内の台帳照合だけに使う。 */
    val modelHashPrefix: String,
    val runtime: LlmRuntimeId,
    val runtimeVersion: String,
    val initializationMillis: Long,
    val inferenceMillis: Long,
    val promptChars: Int,
    val outputChars: Int,
    val failure: LlmFailureKind?,
)

interface LlmTelemetrySink {
    fun record(telemetry: LlmInferenceTelemetry)

    fun recent(): List<LlmInferenceTelemetry>

    fun clearAll()
}

object NoOpLlmTelemetrySink : LlmTelemetrySink {
    override fun record(telemetry: LlmInferenceTelemetry) = Unit

    override fun recent(): List<LlmInferenceTelemetry> = emptyList()

    override fun clearAll() = Unit
}

/** 端末内に最新N件だけ保持する揮発sink。プロセス終了で消える。 */
class InMemoryLlmTelemetrySink(
    private val maxEntries: Int = MAX_TELEMETRY_ENTRIES,
) : LlmTelemetrySink {
    private val lock = Any()
    private var entries: List<LlmInferenceTelemetry> = emptyList()

    override fun record(telemetry: LlmInferenceTelemetry) {
        synchronized(lock) { entries = (listOf(telemetry) + entries).take(maxEntries) }
    }

    override fun recent(): List<LlmInferenceTelemetry> = synchronized(lock) { entries }

    override fun clearAll() {
        synchronized(lock) { entries = emptyList() }
    }
}

/** 端末内に保持する推論テレメトリの最大件数。 */
const val MAX_TELEMETRY_ENTRIES: Int = 20
