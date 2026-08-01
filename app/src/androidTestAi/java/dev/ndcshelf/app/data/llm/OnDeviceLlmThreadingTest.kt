package dev.ndcshelf.app.data.llm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dev.ndcshelf.app.data.local.FileLlmModelStore
import dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * prefillが公称値の1/20しか出ていない原因を切り分ける。
 *
 * `Backend.CPU()` はthreadCountを既定のまま使っている。Pixel 7は8コアあるため、
 * 既定が少数スレッドなら、ここが支配的な要因になりうる。
 *
 * 全蔵書をLLMへ渡すというプロダクトの前提を守れるかは、この結果で決まる。
 * 入力長を変えながらスレッド数を振り、「文字あたりの処理時間」を測る。
 *
 * 匿名fixtureだけを使う。結果は `getExternalFilesDir(null)/llm-threads.txt` へ書く。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmThreadingTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun comparesThreadCounts() {
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

        val out = File(context.getExternalFilesDir(null), "llm-threads.txt")
        out.writeText(
            "device=${android.os.Build.MODEL} cores=${Runtime.getRuntime().availableProcessors()}\n",
        )

        for (threads in listOf(null, 2, 4, 6, 8)) {
            val backend = if (threads == null) Backend.CPU() else Backend.CPU(threads, threads)
            val engine: Engine? =
                runCatching {
                    Engine(
                        EngineConfig(
                            modelPath = requireNotNull(modelFile).absolutePath,
                            backend = backend,
                            maxNumTokens = 2048,
                            cacheDir =
                                context.cacheDir
                                    .resolve("llm-threads/$threads")
                                    .apply { mkdirs() }
                                    .absolutePath,
                        ),
                    ).apply { initialize() }
                }.getOrNull()
            if (engine == null) {
                record(out, "threads=$threads engineInit=FAILED")
                continue
            }
            engine.use { active ->
                // 入力長を変えて、文字あたりの処理時間（prefillの実効速度）を出す。
                for (books in listOf(3, 12, 27)) {
                    val message = compactLibrary(books)
                    val started = System.currentTimeMillis()
                    val result =
                        runCatching {
                            val conversation =
                                active.createConversation(
                                    ConversationConfig(
                                        systemInstruction = Contents.of(SYSTEM),
                                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                                        maxOutputToken = 96,
                                    ),
                                )
                            conversation.use {
                                conversation
                                    .sendMessage(message)
                                    .contents.contents
                                    .filterIsInstance<Content.Text>()
                                    .joinToString(separator = "") { part -> part.text }
                            }
                        }
                    val elapsed = System.currentTimeMillis() - started
                    result
                        .onSuccess { text ->
                            val body = text.substringAfter("</think>", text).trim()
                            record(
                                out,
                                "threads=$threads books=$books chars=${message.length} ms=$elapsed " +
                                    "msPerChar=${"%.1f".format(elapsed.toDouble() / message.length)} " +
                                    "out=${body.take(90).replace('\n', '⏎')}",
                            )
                        }.onFailure { error ->
                            record(
                                out,
                                "threads=$threads books=$books chars=${message.length} ms=$elapsed FAILED ${error.javaClass.simpleName}",
                            )
                        }
                }
            }
        }
        Log.i(TAG, out.readText())
    }

    /**
     * 全蔵書を渡すことを前提にした圧縮表現。
     * 「所有する本すべてを踏まえて助言する」というコンセプトを守るため、冊数は削らず
     * 1冊あたりの情報量を削る。
     */
    private fun compactLibrary(count: Int): String =
        buildString {
            append("次に読む本を1冊選んでください。\n蔵書一覧:\n")
            (1..count).forEach { index ->
                append("b").append(index).append('|')
                append("匿名サンプル図書").append(index).append('|')
                append(NDC_SAMPLES[index % NDC_SAMPLES.size]).append('\n')
            }
        }

    private fun record(
        out: File,
        line: String,
    ) {
        Log.i(TAG, line)
        runCatching { out.appendText(line + "\n") }
    }

    private companion object {
        const val TAG = "NdcShelfLlmThreads"

        const val SYSTEM =
            "あなたは蔵書の助言者です。一覧から1冊だけ選び、" +
                "{\"id\":\"b1\",\"reason\":\"60字以内の日本語\"} の形式だけを出力してください。"

        val NDC_SAMPLES = listOf("007.6", "547.4", "913.6", "410.1", "469.2", "336.1")
    }
}
