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
import dev.ndcshelf.app.domain.ai.AiLibrarianField
import dev.ndcshelf.app.domain.ai.AiLibrarianItem
import dev.ndcshelf.app.domain.ai.AiLibrarianRequest
import dev.ndcshelf.app.domain.ai.llm.LlmAnswerParseResult
import dev.ndcshelf.app.domain.ai.llm.LlmAnswerParser
import dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog
import dev.ndcshelf.app.domain.ai.llm.LlmPromptBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 製品と同じ経路（[LlmPromptBuilder] → 推論 → [LlmAnswerParser]）を実機で通し、
 * 冊数と推論モード抑止の有無を振って「schemaを満たす回答が返るか」を判定する。
 *
 * ここが通らなければ、端末内LLMは製品として成立しない。逆に通れば、
 * 必要な修正（推論モード抑止・prompt上限・contextTokens）が確定する。
 *
 * 匿名fixtureだけを使い、実在の蔵書は読まない。
 * 結果は `getExternalFilesDir(null)/llm-e2e.txt` へ書く。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmEndToEndTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runsProductionPathOnDevice() {
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

        val out = File(context.getExternalFilesDir(null), "llm-e2e.txt")
        out.writeText("device=${android.os.Build.MODEL} model=${model.id}\n")

        val engine =
            Engine(
                EngineConfig(
                    modelPath = requireNotNull(modelFile).absolutePath,
                    backend = Backend.CPU(),
                    // 配布元artifactの公称contextは2,048（台帳の8,192は過大）。
                    maxNumTokens = 2048,
                    cacheDir =
                        context.cacheDir
                            .resolve("llm-e2e")
                            .apply { mkdirs() }
                            .absolutePath,
                ),
            ).apply { initialize() }

        engine.use { active ->
            for (bookCount in listOf(3, 8, 27)) {
                for (noThink in listOf(true, false)) {
                    for (outputTokens in listOf(256, 512)) {
                        attempt(out, active, bookCount, noThink, outputTokens)
                    }
                }
            }
        }
        Log.i(TAG, out.readText())
    }

    private fun attempt(
        out: File,
        engine: Engine,
        bookCount: Int,
        noThink: Boolean,
        outputTokens: Int,
    ) {
        val request = anonymousRequest(bookCount)
        val prompt =
            runCatching { LlmPromptBuilder.build(request) }.getOrElse { error ->
                record(out, "books=$bookCount noThink=$noThink out=$outputTokens promptBuild=REJECTED ${error.message}")
                return
            }
        val label = "books=$bookCount noThink=$noThink out=$outputTokens promptChars=${prompt.text.length}"
        val started = System.currentTimeMillis()
        val generated =
            runCatching {
                val conversation =
                    engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(prompt.systemInstruction),
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                            maxOutputToken = outputTokens,
                        ),
                    )
                conversation.use {
                    val message = if (noThink) prompt.userMessage + NO_THINK else prompt.userMessage
                    conversation
                        .sendMessage(message)
                        .contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { part -> part.text }
                }
            }
        val elapsed = System.currentTimeMillis() - started
        generated
            .onSuccess { raw ->
                val parsed = LlmAnswerParser.parse(raw, prompt.allowedRefs)
                val verdict =
                    when (parsed) {
                        is LlmAnswerParseResult.Valid -> {
                            "VALID entries=${parsed.answer.entries.size} summary=${parsed.answer.summary?.take(60)}"
                        }

                        LlmAnswerParseResult.Invalid -> {
                            "INVALID raw=${raw.takeLast(220).replace('\n', '⏎')}"
                        }
                    }
                record(out, "$label ms=$elapsed chars=${raw.length} $verdict")
            }.onFailure { error ->
                record(out, "$label ms=$elapsed FAILED ${error.javaClass.simpleName}")
            }
    }

    /** 匿名の書誌だけで構成した要求。実在の蔵書は使わない。 */
    private fun anonymousRequest(count: Int): AiLibrarianRequest =
        AiLibrarianRequest(
            question = "次に読む本を選んでください",
            includedFields =
                listOf(
                    AiLibrarianField.TITLE,
                    AiLibrarianField.AUTHOR,
                    AiLibrarianField.PUBLISHER,
                    AiLibrarianField.PUBLISHED_YEAR,
                    AiLibrarianField.NDC,
                ),
            items =
                (1..count).map { index ->
                    AiLibrarianItem(
                        ref = index.toString(),
                        title = "匿名サンプル図書$index：実運用に近い長さの副題を持つ技術書",
                        author = "サンプル著者$index",
                        publisher = "匿名出版社",
                        publishedYear = 2020 + (index % 6),
                        ndcCode = "007.6",
                    )
                },
        )

    private fun record(
        out: File,
        line: String,
    ) {
        Log.i(TAG, line)
        runCatching { out.appendText(line + "\n") }
    }

    private companion object {
        const val TAG = "NdcShelfLlmE2E"

        /** Qwen3の推論モードを止める指示。 */
        const val NO_THINK = " /no_think"
    }
}
