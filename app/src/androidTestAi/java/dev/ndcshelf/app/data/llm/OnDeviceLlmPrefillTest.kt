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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * prefillの実効速度をartifact別に測る。
 *
 * 既定artifact（qwen3_0_6b_mixed_int4）では、時間が
 * 「固定7.8秒 + 54.8ms×入力文字」で説明でき、prefillが約18 tok/s しか出ていない。
 * これはdecode（公称12.9 tok/s）とほぼ同じで、prefillがバッチ処理されていない疑いがある。
 *
 * 出力上限を極小（8トークン）に固定してdecode時間を消し、入力長だけを変えることで
 * prefillの傾きを直接求める。artifactを変えて傾きが改善するかを見る。
 *
 * **「所有する本すべてを踏まえて助言する」というコンセプトを守れるかは、この傾きで決まる。**
 * 全27冊を渡すには入力600〜1,000文字が必要で、8秒に収めるには5ms/文字以下が要る。
 *
 * 匿名fixtureだけを使う。結果は `getExternalFilesDir(null)/llm-prefill.txt` へ書く。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmPrefillTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun measuresPrefillSlopePerArtifact() {
        assumeTrue("推論runtime非同梱", PlatformLlmRuntime.isAvailable())

        val artifacts =
            listOf(
                "default" to
                    context.noBackupFilesDir
                        .resolve("llm-models/qwen3-0-6b-mixed-int4/2026-08-01/qwen3_0_6b_mixed_int4.litertlm"),
                "nothink-ekv1280" to context.noBackupFilesDir.resolve("llm-alt/nothink.litertlm"),
            ).filter { (_, file) -> file.isFile }
        assumeTrue("計測対象のartifactが無い", artifacts.isNotEmpty())

        val out = File(context.getExternalFilesDir(null), "llm-prefill.txt")
        out.writeText("device=${android.os.Build.MODEL}\n")

        artifacts.forEach { (name, file) ->
            // ekv1280のartifactはcontextが1,280。両方に共通で収まる値にする。
            val engine =
                runCatching {
                    Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = Backend.CPU(),
                            maxNumTokens = 1280,
                            cacheDir =
                                context.cacheDir
                                    .resolve("llm-prefill/$name")
                                    .apply { mkdirs() }
                                    .absolutePath,
                        ),
                    ).apply { initialize() }
                }.getOrNull()
            if (engine == null) {
                record(out, "artifact=$name engineInit=FAILED")
                return@forEach
            }
            engine.use { active ->
                // 1回目はweight cache生成が混ざるので捨てる。
                runCatching { generate(active, filler(40), 8) }
                CHAR_TARGETS.forEach { chars ->
                    val message = filler(chars)
                    val started = System.currentTimeMillis()
                    val result = runCatching { generate(active, message, 8) }
                    val elapsed = System.currentTimeMillis() - started
                    result
                        .onSuccess {
                            record(
                                out,
                                "artifact=$name chars=${message.length} ms=$elapsed " +
                                    "msPerChar=${"%.1f".format(elapsed.toDouble() / message.length)}",
                            )
                        }.onFailure { error ->
                            record(out, "artifact=$name chars=${message.length} ms=$elapsed FAILED ${error.javaClass.simpleName}")
                        }
                }
            }
        }
        Log.i(TAG, out.readText())
    }

    private fun generate(
        engine: Engine,
        message: String,
        outputTokens: Int,
    ): String {
        val conversation =
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("簡潔に答えてください。"),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = outputTokens,
                ),
            )
        return conversation.use {
            conversation
                .sendMessage(message)
                .contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { part -> part.text }
        }
    }

    /** 指定文字数ちょうどの匿名入力。蔵書一覧に近い構造にする。 */
    private fun filler(chars: Int): String =
        buildString {
            append("蔵書一覧から1冊選んでください。\n")
            var index = 1
            while (length < chars) {
                append("b")
                    .append(index)
                    .append("|匿名サンプル図書")
                    .append(index)
                    .append("|007.6\n")
                index += 1
            }
        }.take(chars)

    private fun record(
        out: File,
        line: String,
    ) {
        Log.i(TAG, line)
        runCatching { out.appendText(line + "\n") }
    }

    private companion object {
        const val TAG = "NdcShelfLlmPrefill"

        /** 27冊の圧縮表現は約600文字。実運用の想定幅を挟む。 */
        val CHAR_TARGETS = listOf(60, 200, 400, 600, 900)
    }
}
