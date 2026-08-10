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
 * 出力契約を変えたときに、0.6Bモデルが指示へ追従できるかを実機で確かめる。
 *
 * `OnDeviceLlmSweepTest` で「厳格なJSONを要求する現行契約では一度も生成できない」ことが
 * 分かったため、契約を緩めれば成立するのかを切り分ける。ここで全滅なら、モデルの
 * 指示追従能力そのものが不足していると結論できる。
 *
 * `/no_think` の渡し方も、userメッセージ末尾とsystemInstructionの両方を試す。
 *
 * 結果は `getExternalFilesDir(null)/llm-contract.txt` へ書く。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmContractTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun comparesOutputContracts() {
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

        val out = File(context.getExternalFilesDir(null), "llm-contract.txt")
        out.writeText("device=${android.os.Build.MODEL}\n")

        val engine =
            Engine(
                EngineConfig(
                    modelPath = requireNotNull(modelFile).absolutePath,
                    backend = Backend.CPU(),
                    // 配布元artifactの公称contextは2,048。
                    maxNumTokens = 2048,
                    cacheDir =
                        context.cacheDir
                            .resolve("llm-contract")
                            .apply { mkdirs() }
                            .absolutePath,
                ),
            ).apply { initialize() }

        engine.use { active ->
            CONTRACTS.forEach { contract ->
                THINK_SUPPRESSION.forEach { suppression ->
                    run(out, active, contract, suppression)
                }
            }
        }
        Log.i(TAG, out.readText())
    }

    private fun run(
        out: File,
        engine: Engine,
        contract: Contract,
        suppression: ThinkSuppression,
    ) {
        val label = "contract=${contract.name} suppress=${suppression.name}"
        val started = System.currentTimeMillis()
        val result =
            runCatching {
                val system =
                    when (suppression) {
                        ThinkSuppression.SYSTEM -> contract.system + NO_THINK
                        else -> contract.system
                    }
                val message =
                    when (suppression) {
                        ThinkSuppression.MESSAGE -> contract.message + NO_THINK
                        else -> contract.message
                    }
                val conversation =
                    engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(system),
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                            maxOutputToken = OUTPUT_TOKENS,
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
                val body = text.substringAfter("</think>", text)
                record(
                    out,
                    "$label ms=$elapsed chars=${text.length} think=${"<think>" in text} " +
                        "satisfied=${contract.satisfiedBy(body)} body=${body.take(180).replace('\n', '⏎')}",
                )
            }.onFailure { error ->
                record(out, "$label ms=$elapsed FAILED ${error.javaClass.simpleName}")
            }
    }

    private fun record(
        out: File,
        line: String,
    ) {
        Log.i(TAG, line)
        runCatching { out.appendText(line + "\n") }
    }

    private enum class ThinkSuppression { NONE, MESSAGE, SYSTEM }

    /** 出力契約の候補。厳しい順に並べる。 */
    private class Contract(
        val name: String,
        val system: String,
        val message: String,
        val satisfiedBy: (String) -> Boolean,
    )

    private companion object {
        const val TAG = "NdcShelfLlmContract"
        const val NO_THINK = " /no_think"
        const val OUTPUT_TOKENS = 160

        /** 匿名の3冊。実在の蔵書を使わない。 */
        const val ITEMS_JSON =
            "{\"items\":[" +
                "{\"ref\":\"b1\",\"title\":\"匿名サンプル図書A\"}," +
                "{\"ref\":\"b2\",\"title\":\"匿名サンプル図書B\"}," +
                "{\"ref\":\"b3\",\"title\":\"匿名サンプル図書C\"}]}"

        val CONTRACTS =
            listOf(
                // 1. 現行契約。JSONオブジェクトのみを要求する。
                Contract(
                    name = "strictJson",
                    system =
                        "あなたは蔵書の助言者です。回答はJSONオブジェクトだけを出力してください。" +
                            "形式: {\"summary\":\"80字以内\",\"refs\":[\"b1\"]}。前後に説明文を付けないでください。",
                    message = "次に読む本を1冊選んでください。\n$ITEMS_JSON",
                    satisfiedBy = { body -> body.trimStart().startsWith("{") && "\"refs\"" in body },
                ),
                // 2. 1行1件のプレーンテキスト。JSONを求めない。
                Contract(
                    name = "plainLines",
                    system =
                        "あなたは蔵書の助言者です。選んだ本を1行ずつ、必ず " +
                            "ref|理由 の形式だけで出力してください。他の文は書かないでください。",
                    message = "次に読む本を1冊選んでください。\n$ITEMS_JSON",
                    satisfiedBy = { body -> Regex("(?m)^\\s*b[123]\\s*\\|").containsMatchIn(body) },
                ),
                // 3. refだけを返させる最小契約。
                Contract(
                    name = "refOnly",
                    system = "あなたは蔵書の助言者です。選んだ本のrefを1つだけ出力してください。他は何も書かないでください。",
                    message = "次に読む本を1冊選んでください。\n$ITEMS_JSON",
                    satisfiedBy = { body -> Regex("^\\s*b[123]\\s*$", RegexOption.MULTILINE).containsMatchIn(body) },
                ),
                // 4. 自然文だけ。構造を一切求めない（Codexが提案する「語り口だけ」の役割）。
                Contract(
                    name = "proseOnly",
                    system = "あなたは蔵書の助言者です。80字以内の日本語で、1冊をすすめる短い文だけを書いてください。",
                    message = "「匿名サンプル図書A」をすすめる短い文を書いてください。",
                    satisfiedBy = { body -> body.trim().length in 1..400 && "<think>" !in body },
                ),
            )

        val THINK_SUPPRESSION = listOf(ThinkSuppression.NONE, ThinkSuppression.MESSAGE, ThinkSuppression.SYSTEM)
    }
}
