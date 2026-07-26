package dev.ndcshelf.app.data.remote

import dev.ndcshelf.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class NdlBookMetadataService(
    private val parser: NdlSruParser = NdlSruParser(),
    private val callFactory: Call.Factory = defaultNdlHttpClient(),
    private val baseUrl: HttpUrl = NDL_SRU_BASE_URL,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val userAgent: String = "NDC-Shelf/${BuildConfig.VERSION_NAME} (Android)",
) : BookMetadataService {
    override suspend fun findByIsbn(isbn13: String): BookMetadataLookupResult {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = lookupOnce(isbn13)
            if (result !is BookMetadataLookupResult.Failure ||
                !result.reason.retryable ||
                attempt >= MAX_RETRIES
            ) {
                return result
            }
            retryDelay(result.reason.retryDelayMillis(attempt))
            attempt += 1
        }
    }

    private suspend fun lookupOnce(isbn13: String): BookMetadataLookupResult {
        val request = Request.Builder()
            .url(buildNdlSruUrl(isbn13, baseUrl))
            .header("Accept", "application/xml")
            .header("User-Agent", userAgent)
            .get()
            .build()

        val response = try {
            callFactory.newCall(request).await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            return BookMetadataLookupResult.Failure(error.toBookMetadataFailure())
        }

        return response.use {
            when (response.code) {
                429 -> BookMetadataLookupResult.Failure(
                    BookMetadataFailure.RATE_LIMITED,
                    response.code,
                )
                in 500..599 -> BookMetadataLookupResult.Failure(
                    BookMetadataFailure.SERVER,
                    response.code,
                )
                !in 200..299 -> BookMetadataLookupResult.Failure(
                    BookMetadataFailure.CLIENT,
                    response.code,
                )
                else -> parseResponse(response, isbn13)
            }
        }
    }

    private suspend fun parseResponse(
        response: Response,
        isbn13: String,
    ): BookMetadataLookupResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val body = response.body ?: return@withContext BookMetadataLookupResult.Failure(
            BookMetadataFailure.PARSE,
        )
        try {
            val metadata = body.byteStream().buffered().use { parser.parse(it, isbn13) }
            if (metadata == null) {
                BookMetadataLookupResult.NotFound
            } else {
                BookMetadataLookupResult.Found(metadata)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            BookMetadataLookupResult.Failure(BookMetadataFailure.PARSE)
        }
    }
}

internal fun buildNdlSruUrl(
    isbn13: String,
    baseUrl: HttpUrl = NDL_SRU_BASE_URL,
): HttpUrl = baseUrl.newBuilder()
    .addQueryParameter("operation", "searchRetrieve")
    .addQueryParameter("version", "1.2")
    .addQueryParameter("recordSchema", "dcndl")
    .addQueryParameter("recordPacking", "xml")
    .addQueryParameter("onlyBib", "true")
    .addQueryParameter("maximumRecords", "1")
    .addQueryParameter("query", "isbn=\"$isbn13\"")
    .build()

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, cancelledResponse, _ ->
                    cancelledResponse.close()
                }
            }
        },
    )
}

internal fun IOException.toBookMetadataFailure(): BookMetadataFailure = when (this) {
    is SocketTimeoutException -> BookMetadataFailure.TIMEOUT
    is UnknownHostException, is ConnectException, is NoRouteToHostException ->
        BookMetadataFailure.OFFLINE
    else -> BookMetadataFailure.NETWORK
}

private fun BookMetadataFailure.retryDelayMillis(attempt: Int): Long =
    if (this == BookMetadataFailure.RATE_LIMITED) {
        RATE_LIMIT_DELAY_MILLIS
    } else {
        RETRY_BASE_DELAY_MILLIS shl attempt
    }

private fun defaultNdlHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

private val NDL_SRU_BASE_URL = HttpUrl.Builder()
    .scheme("https")
    .host("ndlsearch.ndl.go.jp")
    .addPathSegments("api/sru")
    .build()

private const val MAX_RETRIES = 1
private const val RETRY_BASE_DELAY_MILLIS = 750L
private const val RATE_LIMIT_DELAY_MILLIS = 3_000L
