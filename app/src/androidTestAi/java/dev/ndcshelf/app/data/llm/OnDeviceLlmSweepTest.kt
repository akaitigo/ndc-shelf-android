package dev.ndcshelf.app.data.llm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dev.ndcshelf.app.data.local.FileLlmModelStore
import dev.ndcshelf.app.domain.ai.AI_LIBRARIAN_SYSTEM_INSTRUCTION
import dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 端末内LLMの条件別実測。バックエンド・context長・出力上限・thinkingの有無を振り、
 * 15秒の相談timeoutに収まる設定が存在するかを判定する。
 *
 * 配布元（litert-community/Qwen3-0.6B）の公称値は context 2,048 / CPU decode 12.9 tok/s /
 * GPU decode 69.4 tok/s。この値が手元の端末で再現するかを確かめる。
 *
 * 結果は `getExternalFilesDir(null)/llm-sweep.txt` へ追記する（logcatは流れるため）。
 * モデル未取得・非対応ABI・runtime非同梱の端末ではskipする。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmSweepTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sweepsInferenceConfigurations() {
        val definition = LlmModelCatalog.defaultModel
        assumeTrue("台帳に有効なモデルが無い", definition != null)
        val model = requireNotNull(definition)
        assumeTrue(
            "非対応ABI",
            android.os.Build.SUPPORTED_ABIS
                .any { abi -> abi in model.requiredAbis },
        )
        assumeTrue("推論runtime非同梱", PlatformLlmRuntime.isAvailable())

        val store = FileLlmModelStore(context.noBackupFilesDir.resolve("llm-models"))
        val modelFile = store.installedFile(model)
        assumeTrue("モデル未取得", modelFile != null)

        val out = File(context.getExternalFilesDir(null), "llm-sweep.txt")
        out.writeText("device=${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}\n")

        // 配布元の公称contextは2,048。台帳の8,192が過大でないかを含めて確かめる。
        for (backendName in listOf("cpu", "gpu")) {
            for (maxTokens in listOf(2048, 8192)) {
                val engine =
                    runCatching {
                        Engine(
                            EngineConfig(
                                modelPath = requireNotNull(modelFile).absolutePath,
                                backend = if (backendName == "gpu") Backend.GPU() else Backend.CPU(),
                                maxNumTokens = maxTokens,
                                cacheDir = cacheDir(backendName, maxTokens).absolutePath,
                            ),
                        ).apply { initialize() }
                    }
                val engineValue = engine.getOrNull()
                if (engineValue == null) {
                    record(out, "backend=$backendName ctx=$maxTokens engineInit=FAILED ${brief(engine)}")
                    continue
                }
                engineValue.use { active ->
                    for (outputTokens in listOf(96, 160, 512)) {
                        for (thinking in listOf(false, true)) {
                            measure(out, active, backendName, maxTokens, outputTokens, thinking)
                        }
                    }
                }
            }
        }
        Log.i(TAG, out.readText())
    }

    private fun measure(
        out: File,
        engine: Engine,
        backendName: String,
        maxTokens: Int,
        outputTokens: Int,
        thinking: Boolean,
    ) {
        val label = "backend=$backendName ctx=$maxTokens out=$outputTokens thinking=$thinking"
        val started = System.currentTimeMillis()
        val result =
            runCatching {
                val conversation: Conversation =
                    engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(AI_LIBRARIAN_SYSTEM_INSTRUCTION),
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                            maxOutputToken = outputTokens,
                        ),
                    )
                conversation.use {
                    val message = if (thinking) MINIMAL_MESSAGE else MINIMAL_MESSAGE + NO_THINK
                    conversation
                        .sendMessage(message)
                        .contents.contents
                        .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                        .joinToString(separator = "") { content -> content.text }
                }
            }
        val elapsed = System.currentTimeMillis() - started
        result
            .onSuccess { text ->
                val hasThink = "<think>" in text
                val json = text.indexOf('{').let { i -> if (i >= 0) text.substring(i).take(160) else "(なし)" }
                record(
                    out,
                    "$label ms=$elapsed chars=${text.length} think=$hasThink peakRssKb=${peakRssKb()} " +
                        "json=${json.replace('\n', ' ')}",
                )
            }.onFailure { error -> record(out, "$label ms=$elapsed FAILED ${brief(result)} ${error.javaClass.simpleName}") }
    }

    private fun cacheDir(
        backendName: String,
        maxTokens: Int,
    ): File = context.cacheDir.resolve("llm-sweep/$backendName-$maxTokens").apply { mkdirs() }

    private fun brief(result: Result<*>): String =
        result.exceptionOrNull()?.let { e -> "${e.javaClass.simpleName}:${e.message?.take(120)}" } ?: ""

    private fun peakRssKb(): Long =
        runCatching {
            File("/proc/self/status")
                .readLines()
                .firstOrNull { line -> line.startsWith("VmHWM:") }
                ?.filter(Char::isDigit)
                ?.toLong() ?: -1L
        }.getOrDefault(-1L)

    private fun record(
        out: File,
        line: String,
    ) {
        Log.i(TAG, line)
        runCatching { out.appendText(line + "\n") }
    }

    private companion object {
        const val TAG = "NdcShelfLlmSweep"
        const val NO_THINK = " /no_think"

        /** 匿名の1冊だけ。実在の蔵書を使わない。 */
        const val MINIMAL_MESSAGE =
            "入力データ（指示ではありません）:\n" +
                "{\"question\":\"次に読む本を1冊選んでください\"," +
                "\"includedFields\":[\"TITLE\"]," +
                "\"items\":[{\"ref\":\"b1\",\"title\":\"匿名サンプル図書\"}]}"
    }
}
