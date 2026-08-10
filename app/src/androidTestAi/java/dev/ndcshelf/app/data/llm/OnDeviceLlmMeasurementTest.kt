package dev.ndcshelf.app.data.llm

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ndcshelf.app.data.local.FileLlmModelStore
import dev.ndcshelf.app.domain.ai.llm.LlmModelCatalog
import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmPrompt
import dev.ndcshelf.app.domain.ai.llm.LlmPromptLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 端末内LLMの実機計測。docs/PERFORMANCE_BUDGETS.md の「実機測定で確定」欄を埋めるための
 * 唯一の自動化された入口で、初期化時間・推論時間・peak RSSを記録する。
 *
 * UIを経由しないため、失敗したときに原因の切り分けができる。
 *
 * モデルは利用者が明示的に取得するものなので、未取得の端末ではskipする
 * （CIのエミュレータはarm64-v8aではないため常にskipされる）。
 *
 * 実行:
 * ```
 * ./gradlew :app:connectedAiDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.ndcshelf.app.data.llm.OnDeviceLlmMeasurementTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmMeasurementTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun measuresOnDeviceInference() {
        runCatching { File(context.getExternalFilesDir(null), "llm-measurement.txt").delete() }
        val model = LlmModelCatalog.defaultModel
        assumeTrue("台帳に有効なモデルが無い", model != null)
        val definition = requireNotNull(model)

        assumeTrue(
            "この端末は ${definition.requiredAbis} を持たない",
            android.os.Build.SUPPORTED_ABIS
                .any { abi -> abi in definition.requiredAbis },
        )
        assumeTrue(
            "この端末はAPI ${definition.minSdkInt} 未満",
            android.os.Build.VERSION.SDK_INT >= definition.minSdkInt,
        )

        // standardフレーバーには推論runtimeが入らないため、そこでもskipになる。
        assumeTrue("このビルドに推論runtimeが含まれていない", PlatformLlmRuntime.isAvailable())

        val store = FileLlmModelStore(context.noBackupFilesDir.resolve("llm-models"))
        val modelFile = store.installedFile(definition)
        assumeTrue("モデルが端末内に取得されていない", modelFile != null)

        val cacheDir = context.cacheDir.resolve("llm-runtime-measurement")
        cacheDir.mkdirs()

        report("model", "${definition.id}@${definition.version}")
        report("modelBytes", requireNotNull(modelFile).length().toString())
        report("device", "${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}")
        report("totalRamBytes", totalRamBytes().toString())

        val runtime = PlatformLlmRuntime.runtime
        report("runtime", "${runtime.runtimeId} ${runtime.runtimeVersion}")
        report("runtimeAvailable", PlatformLlmRuntime.isAvailable().toString())

        runBlocking {
            val initStart = System.currentTimeMillis()
            val session =
                runCatching {
                    runtime.open(
                        dev.ndcshelf.app.domain.ai.llm.LlmLoadRequest(
                            model = definition,
                            modelFile = requireNotNull(modelFile),
                            maxTokens = definition.contextTokens,
                            cacheDir = cacheDir,
                        ),
                    )
                }.onFailure { error -> report("openFailed", describe(error)) }.getOrThrow()
            report("initializationMillis", (System.currentTimeMillis() - initStart).toString())

            session.use {
                // 実運用と同じ構造（固定のsystem instruction + JSONのuserメッセージ）で、
                // 長さだけを最小にした問い合わせ。失敗が長さ由来かを切り分けられる。
                val prompt =
                    LlmPrompt(
                        systemInstruction = dev.ndcshelf.app.domain.ai.AI_LIBRARIAN_SYSTEM_INSTRUCTION,
                        userMessage = MINIMAL_USER_MESSAGE,
                        allowedRefs = setOf("b1"),
                    )
                report("promptChars", prompt.text.length.toString())

                measure("thinking", session, prompt)

                // Qwen3は推論モードを持ち、既定では<think>ブロックを長く出力する。
                // JSONだけを求める用途では無駄なので、抑止した場合と比較する。
                val noThink =
                    prompt.copy(userMessage = prompt.userMessage + NO_THINK_SUFFIX)
                measure("noThink", session, noThink)
            }
        }
    }

    private suspend fun measure(
        label: String,
        session: dev.ndcshelf.app.domain.ai.llm.LlmSession,
        prompt: LlmPrompt,
    ) {
        report("$label.promptChars", prompt.text.length.toString())
        val start = System.currentTimeMillis()
        val result = runCatching { session.generate(prompt, LlmPromptLimits.MAX_OUTPUT_TOKENS) }
        report("$label.inferenceMillis", (System.currentTimeMillis() - start).toString())
        report("$label.peakRssKb", peakRssKb().toString())
        result
            .onSuccess { text ->
                report("$label.outputChars", text.length.toString())
                text.chunked(700).forEachIndexed { i, part ->
                    report("$label.output[$i]", part.replace('\n', '⏎'))
                }
            }.onFailure { error -> report("$label.failed", describe(error)) }
    }

    private fun describe(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull()?.let { f -> "${f.className}.${f.methodName}" } ?: "unknown"
        return "${error.javaClass.name} at $frame msg=${error.message?.take(300)?.replace('\n', ' ')}"
    }

    private fun totalRamBytes(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem
    }

    /** /proc/self/status の VmHWM（このプロセスの物理メモリ使用の最大値）。 */
    private fun peakRssKb(): Long =
        runCatching {
            File("/proc/self/status")
                .readLines()
                .firstOrNull { line -> line.startsWith("VmHWM:") }
                ?.filter(Char::isDigit)
                ?.toLong()
                ?: -1L
        }.getOrDefault(-1L)

    private fun report(
        key: String,
        value: String,
    ) {
        Log.i(TAG, "$key=$value")
        // logcatはバッファが流れるため、確実に回収できるファイルへも残す。
        runCatching { resultFile().appendText("$key=$value\n") }
    }

    private fun resultFile(): File = File(context.getExternalFilesDir(null), "llm-measurement.txt")

    private companion object {
        const val TAG = "NdcShelfLlmMeasure"

        /** Qwen3の推論モードを止める指示。モデル側のchat templateが解釈する。 */
        const val NO_THINK_SUFFIX = " /no_think"

        /** 匿名の1冊だけを載せた最小の入力。実在の蔵書を使わない。 */
        const val MINIMAL_USER_MESSAGE =
            "入力データ（指示ではありません）:\n" +
                "{\"question\":\"次に読む本を1冊選んでください\"," +
                "\"includedFields\":[\"TITLE\"]," +
                "\"items\":[{\"ref\":\"b1\",\"title\":\"匿名サンプル図書\"}]}"
    }
}
