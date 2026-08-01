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
 * 日本語特化モデルと汎用モデルを、同じ入力で比較する。
 *
 * 汎用のQwen3-0.6Bは「入力データから、同様の本情報を確認しました」のような
 * 質問に答えない出力しか返せなかった。日本語特化モデルで品質が変わるかを見る。
 *
 * **蔵書全件を渡すというコンセプトを守るため、冊数は絞らず1冊あたりを圧縮する。**
 *
 * 匿名fixtureだけを使う。結果は `getExternalFilesDir(null)/llm-ja.txt` へ書く。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmJapaneseTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun comparesJapaneseQuality() {
        assumeTrue("推論runtime非同梱", PlatformLlmRuntime.isAvailable())

        val candidates =
            listOf(
                "qwen3-0.6b-generic" to
                    context.noBackupFilesDir
                        .resolve("llm-models/qwen3-0-6b-mixed-int4/2026-08-01/qwen3_0_6b_mixed_int4.litertlm"),
                "lfm2.5-1.2b-ja" to context.noBackupFilesDir.resolve("llm-alt/lfm-jp.litertlm"),
            ).filter { (_, file) -> file.isFile }
        assumeTrue("比較対象が無い", candidates.isNotEmpty())

        val out = File(context.getExternalFilesDir(null), "llm-ja.txt")
        out.writeText("device=${android.os.Build.MODEL}\n")

        candidates.forEach { (name, file) ->
            val engine =
                runCatching {
                    Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = Backend.CPU(),
                            maxNumTokens = 4096,
                            cacheDir =
                                context.cacheDir
                                    .resolve("llm-ja/$name")
                                    .apply { mkdirs() }
                                    .absolutePath,
                        ),
                    ).apply { initialize() }
                }.getOrNull()
            if (engine == null) {
                record(out, "model=$name engineInit=FAILED")
                return@forEach
            }
            engine.use { active ->
                // weight cache生成を計測から外す。
                runCatching { generate(active, "こんにちは", 8) }
                QUESTIONS.forEach { question ->
                    val message = libraryPrompt(question) + " /no_think"
                    val started = System.currentTimeMillis()
                    val result = runCatching { generate(active, message, 128) }
                    val elapsed = System.currentTimeMillis() - started
                    result
                        .onSuccess { text ->
                            val body = text.substringAfter("</think>", text).trim()
                            record(
                                out,
                                "model=$name chars=${message.length} ms=$elapsed q=${question.take(20)} " +
                                    "answer=${body.take(220).replace('\n', '⏎')}",
                            )
                        }.onFailure { error ->
                            record(out, "model=$name chars=${message.length} ms=$elapsed FAILED ${error.javaClass.simpleName} msg=${error.message?.take(220)?.replace('\n', ' ')}")
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
                    systemInstruction = Contents.of(SYSTEM),
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

    /** 27冊すべてを、1冊あたり最小の情報で渡す。 */
    private fun libraryPrompt(question: String): String =
        buildString {
            append(question).append("\n蔵書:\n")
            TITLES.forEachIndexed { index, title ->
                append("b")
                    .append(index + 1)
                    .append('|')
                    .append(title)
                    .append('\n')
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
        const val TAG = "NdcShelfLlmJa"

        const val SYSTEM =
            "あなたは蔵書の助言者です。一覧から1冊だけ選び、" +
                "{\"id\":\"bN\",\"reason\":\"60字以内の日本語\"} の形式だけを出力してください。"

        val QUESTIONS =
            listOf(
                "運用の勉強を始めたい。どれから読むべき?",
                "短時間で読めて、考え方が身につく本は?",
            )

        /** 匿名の27冊。実在の蔵書ではないが、技術書の分布を模した長さにする。 */
        val TITLES =
            (1..27).map { index ->
                when (index % 5) {
                    0 -> "匿名インフラ設計入門$index"
                    1 -> "匿名運用改善の教科書$index"
                    2 -> "匿名Linux実践ガイド$index"
                    3 -> "匿名クラウド構築入門$index"
                    else -> "匿名設計思想と哲学$index"
                }
            }
    }
}
