package dev.ndcshelf.app.data.remote

import dev.ndcshelf.app.BuildConfig
import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmModelSource
import dev.ndcshelf.app.domain.ai.llm.LlmModelUrlPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * モデル取得だけを行う外部通信経路（docs/NETWORK_BOUNDARY.md）。
 *
 * 推論経路はこのクラスへ一切依存しない。送信する値は台帳のURLとUser-Agentだけで、
 * 蔵書・質問文・回答は一切送らない。
 *
 * 安全策:
 * - HTTPS・許可host・port 443のみ（[LlmModelUrlPolicy]）
 * - redirectを追わない（3xxは失敗として扱う）
 * - Content-Lengthが台帳の期待サイズと一致しなければ本文を読まない
 * - 実体のサイズ上限とSHA-256は[dev.ndcshelf.app.data.local.FileLlmModelStore]が再検証する
 */
class LlmModelDownloadSource(
    private val definition: LlmModelDefinition,
    private val callFactory: Call.Factory = defaultLlmModelHttpClient(),
    private val userAgent: String = "NDC-Shelf/${BuildConfig.VERSION_NAME} (Android)",
) : LlmModelSource {
    override suspend fun open(): InputStream {
        if (!LlmModelUrlPolicy.isAllowed(definition.downloadUrl)) {
            throw IOException("Blocked model URL")
        }
        val request =
            Request
                .Builder()
                .url(definition.downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", userAgent)
                .get()
                .build()
        val response = callFactory.newCall(request).await()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Model download rejected: ${response.code}")
        }
        val body = response.body
        if (body == null) {
            response.close()
            throw IOException("Model download returned no body")
        }
        val declaredLength = body.contentLength()
        if (declaredLength >= 0 && declaredLength != definition.sizeBytes) {
            response.close()
            throw IOException("Model download size mismatch")
        }
        return ResponseInputStream(response, body.byteStream())
    }

    /** streamを閉じたときにHTTP responseも閉じる。 */
    private class ResponseInputStream(
        private val response: Response,
        private val delegate: InputStream,
    ) : InputStream() {
        override fun read(): Int = delegate.read()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(buffer, offset, length)

        override fun available(): Int = delegate.available()

        override fun close() {
            runCatching { delegate.close() }
            response.close()
        }
    }
}

/**
 * モデル取得専用のOkHttpクライアント。数百MiB〜数GiBを扱うため読み取りtimeoutは
 * 長めにするが、全体callのtimeoutは設けずキャンセルで打ち切る。
 */
fun defaultLlmModelHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (continuation.isCancelled) {
                        response.close()
                        return
                    }
                    continuation.resume(response)
                }
            },
        )
    }
