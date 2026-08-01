package dev.ndcshelf.app.data.llm

import android.os.Build
import dev.ndcshelf.app.domain.ai.llm.LlmInferenceRuntime

/**
 * aiフレーバーの推論runtime。LiteRT-LM（Apache-2.0）のnative libraryを同梱する。
 *
 * [isAvailable]はAPI levelとnative libraryの読み込み可否だけで判定し、判定できない
 * 場合はfalseへ倒す。standardフレーバーには同名の代替実装（常にfalse）が入る。
 */
object PlatformLlmRuntime {
    val runtime: LlmInferenceRuntime = LiteRtLmInferenceRuntime()

    /**
     * native libraryを読み込めるか。
     *
     * LiteRT-LM 0.15.0はminSdk 24でarm64-v8a／x86_64のみを提供し、本アプリは
     * arm64-v8aだけを同梱する。API 23端末と非対応ABIでは読み込みに失敗するため、
     * ここでfalseになり[dev.ndcshelf.app.domain.ai.llm.LlmCapabilityChecker]が
     * 取得も起動も抑止する。
     */
    fun isAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < LITERT_LM_MIN_SDK) return false
        if (Build.SUPPORTED_ABIS?.contains(LITERT_LM_ABI) != true) return false
        // LiteRT-LMのローダーはinternalなので、同梱している共有ライブラリを直接読めるかで判定する。
        // System.loadLibraryは同じClassLoaderで冪等なため、後続の初期化と競合しない。
        return runCatching { System.loadLibrary(LITERT_LM_LIBRARY) }.isSuccess
    }

    /** LiteRT-LM 0.15.0のAndroidManifestが宣言するminSdkVersion。 */
    const val LITERT_LM_MIN_SDK: Int = 24

    /** 本アプリが同梱するLiteRT-LMのABI。 */
    const val LITERT_LM_ABI: String = "arm64-v8a"

    /** AARが同梱する共有ライブラリ名（`liblitertlm_jni.so`）。 */
    private const val LITERT_LM_LIBRARY: String = "litertlm_jni"
}
