package dev.ndcshelf.app.data.remote

import dev.ndcshelf.app.domain.ai.llm.LlmModelDefinition
import dev.ndcshelf.app.domain.ai.llm.LlmRuntimeId
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * モデル取得経路の通信検証。MockWebServerで、許可外URL・redirect・サイズ不一致・
 * エラー応答を拒否することと、送信するheaderに個人データが含まれないことを確かめる。
 */
class LlmModelDownloadSourceTest {
    private lateinit var server: MockWebServer

    private val payload = "model-bytes".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `download url outside the allowlist is blocked before any request`() =
        runTest {
            // 台帳の定義自体が許可URLしか受け付けないため、生成時点で拒否される。
            assertThrows(IllegalArgumentException::class.java) {
                definition(url = server.url("/model.bin").toString())
            }
        }

    @Test
    fun `a redirect outside the allowlist is refused`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://evil.example.com/model.bin"),
            )
            val source = sourceAgainstServer()

            val error = assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.open() } }

            assertTrue("allowlist" in error.message.orEmpty())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a single redirect inside the allowlist is followed`() =
        runTest {
            // 配布元はCDNの署名付きURLへ302で誘導する。追従先も許可ドメイン内であること。
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader(
                        "Location",
                        "https://us.aws.cdn.hf.co/xet-bridge-us/abc/def?Expires=1&Signature=2",
                    ),
            )
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            val source = sourceAgainstServer()

            val bytes = source.open().use { stream -> stream.readBytes() }

            assertTrue(payload.contentEquals(bytes))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `a second redirect is refused`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://us.aws.cdn.hf.co/first"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://us.aws.cdn.hf.co/second"),
            )
            val source = sourceAgainstServer()

            val error = assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.open() } }

            assertTrue("too many redirects" in error.message.orEmpty())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `error responses are rejected`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            val source = sourceAgainstServer()

            assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.open() } }
        }

    @Test
    fun `declared content length must match the catalog size`() =
        runTest {
            server.enqueue(MockResponse().setBody("too-long-body-for-the-catalog"))
            val source = sourceAgainstServer()

            val error = assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { source.open() } }

            assertTrue("size mismatch" in error.message.orEmpty())
        }

    @Test
    fun `successful download streams the body and sends no personal data`() =
        runTest {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            val source = sourceAgainstServer()

            val bytes = source.open().use { stream -> stream.readBytes() }

            assertTrue(payload.contentEquals(bytes))
            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("application/octet-stream", recorded.getHeader("Accept"))
            assertTrue(recorded.getHeader("User-Agent").orEmpty().startsWith("NDC-Shelf/"))
            assertEquals(null, recorded.getHeader("Cookie"))
            assertEquals(null, recorded.getHeader("Authorization"))
            assertTrue(recorded.body.size == 0L)
        }

    @Test
    fun `default client refuses redirects and retries`() {
        val client = defaultLlmModelHttpClient()

        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
        assertEquals(false, client.retryOnConnectionFailure)
    }

    /**
     * URLポリシーは台帳定義で強制されるため、通信そのものの検証では
     * MockWebServerのURLへ差し替えたclientを使う。
     */
    private fun sourceAgainstServer(): LlmModelDownloadSource {
        val target: HttpUrl = server.url("/model.bin")
        val client =
            OkHttpClient
                .Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        return LlmModelDownloadSource(
            definition = definition(),
            // 追従先の検査は本番のURLポリシーで行い、実際の接続だけをMockWebServerへ差し替える。
            callFactory = { request: Request ->
                client.newCall(
                    request
                        .newBuilder()
                        .url(target.newBuilder().encodedPath(request.url.encodedPath).build())
                        .build(),
                )
            },
        )
    }

    private fun definition(url: String = "https://huggingface.co/ndc-shelf-test/model/resolve/main/model.bin"): LlmModelDefinition =
        LlmModelDefinition(
            id = "test-model",
            version = "1.0.0",
            displayName = "テストモデル",
            runtime = LlmRuntimeId.FAKE,
            downloadUrl = url,
            fileName = "model.bin",
            sizeBytes = payload.size.toLong(),
            sha256 = "0".repeat(64),
            licenseSpdxId = "Apache-2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            sourceUrl = "https://huggingface.co/ndc-shelf-test/model",
            verifiedOn = "2026-08-01",
            minSdkInt = 24,
            requiredAbis = setOf("arm64-v8a"),
            minTotalRamBytes = 1,
            requiredFreeBytes = payload.size.toLong(),
            contextTokens = 2048,
            addedOn = "2026-08-01",
        )
}
