package dev.ndcshelf.app.data.remote

import dev.ndcshelf.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.source
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class NdlSeriesReleaseService(
    private val parser: NdlSeriesReleaseParser = NdlSeriesReleaseParser(),
    private val callFactory: Call.Factory = defaultSeriesHttpClient(),
    private val baseUrl: HttpUrl = NDL_SERIES_SRU_URL,
    private val userAgent: String = "NDC-Shelf/${BuildConfig.VERSION_NAME} (Android)",
) : SeriesReleaseSource {
    override suspend fun search(queryTitle: String, fromYear: Int): SeriesReleaseSourceResult {
        val title = queryTitle.trim()
        if (title.isBlank() || title.length > MAX_QUERY_CHARACTERS || title.any(Char::isISOControl) ||
            fromYear !in 1000..9999
        ) {
            return SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.CLIENT)
        }
        val request = Request.Builder()
            .url(buildNdlSeriesReleaseUrl(title, fromYear, baseUrl))
            .header("Accept", "application/xml")
            .header("User-Agent", userAgent)
            .get()
            .build()
        val response = try {
            callFactory.newCall(request).awaitSeriesResponse()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            return SeriesReleaseSourceResult.Failure(error.toSeriesReleaseFailure())
        }
        return response.use {
            when (response.code) {
                429 -> SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.RATE_LIMITED)
                in 500..599 -> SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.SERVER)
                !in 200..299 -> SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.CLIENT)
                else -> parse(response)
            }
        }
    }

    private suspend fun parse(response: Response): SeriesReleaseSourceResult = withContext(Dispatchers.IO) {
        val body = response.body
            ?: return@withContext SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.PARSE)
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            return@withContext SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.PARSE)
        }
        try {
            val bounded = body.byteStream().source().buffer().inputStream().limited(MAX_RESPONSE_BYTES)
            SeriesReleaseSourceResult.Found(bounded.use(parser::parse))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SeriesReleaseSourceResult.Failure(SeriesReleaseSourceFailure.PARSE)
        }
    }
}

internal fun buildNdlSeriesReleaseUrl(
    queryTitle: String,
    fromYear: Int,
    baseUrl: HttpUrl = NDL_SERIES_SRU_URL,
): HttpUrl = baseUrl.newBuilder()
    .addQueryParameter("operation", "searchRetrieve")
    .addQueryParameter("version", "1.2")
    .addQueryParameter("recordSchema", "dcndl")
    .addQueryParameter("recordPacking", "xml")
    .addQueryParameter("onlyBib", "true")
    .addQueryParameter("maximumRecords", "20")
    .addQueryParameter(
        "query",
        "dpid=\"open\" AND title=\"${queryTitle.escapeCql()}\" AND from=\"$fromYear\"",
    )
    .build()

private fun String.escapeCql(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private suspend fun Call.awaitSeriesResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, cancelled, _ -> cancelled.close() }
        }
    })
}

private fun java.io.InputStream.limited(maxBytes: Long): java.io.InputStream =
    object : java.io.FilterInputStream(this) {
        private var readBytes = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count(it) }
        private fun count(count: Int) {
            readBytes += count
            if (readBytes > maxBytes) throw IOException("NDL response exceeds limit")
        }
    }

private fun IOException.toSeriesReleaseFailure(): SeriesReleaseSourceFailure = when (this) {
    is SocketTimeoutException -> SeriesReleaseSourceFailure.TIMEOUT
    is UnknownHostException, is ConnectException, is NoRouteToHostException ->
        SeriesReleaseSourceFailure.OFFLINE
    else -> SeriesReleaseSourceFailure.NETWORK
}

private fun defaultSeriesHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

private val NDL_SERIES_SRU_URL = HttpUrl.Builder()
    .scheme("https")
    .host("ndlsearch.ndl.go.jp")
    .addPathSegments("api/sru")
    .build()
private const val MAX_QUERY_CHARACTERS = 200
private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
