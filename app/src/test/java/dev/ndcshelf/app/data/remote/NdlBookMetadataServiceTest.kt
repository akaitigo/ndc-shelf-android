package dev.ndcshelf.app.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NdlBookMetadataServiceTest {
    private lateinit var server: MockWebServer

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
    fun requestUsesTheDocumentedXmlContractAndParsesMetadata() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(BOOK_RESPONSE))
        val service = service()

        val result = service.findByIsbn(ISBN) as BookMetadataLookupResult.Found
        val request = server.takeRequest()

        assertEquals("図書館の本", result.metadata.title)
        assertEquals("GET", request.method)
        assertEquals("application/xml", request.getHeader("Accept"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("NDC-Shelf/"))
        assertEquals("searchRetrieve", request.requestUrl?.queryParameter("operation"))
        assertEquals("1.2", request.requestUrl?.queryParameter("version"))
        assertEquals("dcndl", request.requestUrl?.queryParameter("recordSchema"))
        assertEquals("xml", request.requestUrl?.queryParameter("recordPacking"))
        assertEquals("true", request.requestUrl?.queryParameter("onlyBib"))
        assertEquals("1", request.requestUrl?.queryParameter("maximumRecords"))
        assertEquals("isbn=\"$ISBN\"", request.requestUrl?.queryParameter("query"))
    }

    @Test
    fun productionEndpointIsHttpsAndLimitedToNdlSru() {
        val endpoint = buildNdlSruUrl(ISBN)

        assertEquals("https", endpoint.scheme)
        assertEquals("ndlsearch.ndl.go.jp", endpoint.host)
        assertEquals("/api/sru", endpoint.encodedPath)
    }

    @Test
    fun emptySuccessfulResponseIsNotFound() = runBlocking {
        server.enqueue(MockResponse().setBody("<searchRetrieveResponse />"))

        assertEquals(BookMetadataLookupResult.NotFound, service().findByIsbn(ISBN))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun clientErrorIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = service().findByIsbn(ISBN) as BookMetadataLookupResult.Failure

        assertEquals(BookMetadataFailure.CLIENT, result.reason)
        assertEquals(400, result.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun redirectIsRejectedInsteadOfFollowingAnotherHost() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://example.com/collect/$ISBN"),
        )

        val result = service().findByIsbn(ISBN) as BookMetadataLookupResult.Failure

        assertEquals(BookMetadataFailure.CLIENT, result.reason)
        assertEquals(302, result.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rateLimitAndServerErrorsUseOneBoundedBackoffRetry() = runBlocking {
        val delays = mutableListOf<Long>()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        val service = service(retryDelay = { delays += it })

        val result = service.findByIsbn(ISBN) as BookMetadataLookupResult.Failure

        assertEquals(BookMetadataFailure.SERVER, result.reason)
        assertEquals(503, result.httpStatus)
        assertEquals(listOf(3_000L), delays)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun malformedXmlIsAParseFailureWithoutRetry() = runBlocking {
        server.enqueue(MockResponse().setBody("<not-closed>"))

        val result = service().findByIsbn(ISBN) as BookMetadataLookupResult.Failure

        assertEquals(BookMetadataFailure.PARSE, result.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun networkExceptionsAreClassifiedForRetryUi() {
        assertEquals(
            BookMetadataFailure.OFFLINE,
            UnknownHostException("offline").toBookMetadataFailure(),
        )
        assertEquals(
            BookMetadataFailure.TIMEOUT,
            SocketTimeoutException("slow").toBookMetadataFailure(),
        )
    }

    @Test
    fun cancellationCancelsTheInFlightHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val lookup = async { service().findByIsbn(ISBN) }
        yield()
        server.takeRequest()

        lookup.cancel()

        try {
            lookup.await()
            throw AssertionError("CancellationException expected")
        } catch (_: CancellationException) {
            // Expected: cancellation is never converted into a network failure.
        }
    }

    private fun service(
        retryDelay: suspend (Long) -> Unit = {},
    ) = NdlBookMetadataService(
        callFactory = OkHttpClient.Builder()
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build(),
        baseUrl = server.url("/api/sru"),
        retryDelay = retryDelay,
    )

    private companion object {
        const val ISBN = "9784820418078"
        val BOOK_RESPONSE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <searchRetrieveResponse
                xmlns:dcterms="http://purl.org/dc/terms/"
                xmlns:dcndl="http://ndl.go.jp/dcndl/terms/"
                xmlns:foaf="http://xmlns.com/foaf/0.1/">
                <records><record><recordData><dcndl:BibResource>
                    <dcterms:title>図書館の本</dcterms:title>
                    <dcterms:creator><foaf:Agent><foaf:name>山田太郎</foaf:name></foaf:Agent></dcterms:creator>
                </dcndl:BibResource></recordData></record></records>
            </searchRetrieveResponse>
        """.trimIndent()
    }
}
