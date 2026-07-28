package dev.ndcshelf.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NdlSeriesReleaseServiceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    @Test
    fun requestUsesOpenMetadataTitleAndYearAndParsesMultipleRecords() = runBlocking {
        server.enqueue(MockResponse().setBody(RESPONSE))

        val result = service().search("年代記 \"完全版\"", 2025) as SeriesReleaseSourceResult.Found
        val request = server.takeRequest()

        assertEquals(2, result.candidates.size)
        assertEquals("年代記 完全版 3", result.candidates[0].title)
        assertEquals("9784820418078", result.candidates[0].isbn13)
        assertEquals("https://id.ndl.go.jp/bib/1", result.candidates[0].sourceRecordId)
        assertEquals("20", request.requestUrl?.queryParameter("maximumRecords"))
        assertEquals(
            "dpid=\"open\" AND title=\"年代記 \\\"完全版\\\"\" AND from=\"2025\"",
            request.requestUrl?.queryParameter("query"),
        )
    }

    @Test
    fun redirectsMalformedXmlAndOversizedBodiesFailClosed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com"))
        server.enqueue(MockResponse().setBody("<broken>"))
        server.enqueue(
            MockResponse().setHeader("Content-Length", 2L * 1024 * 1024 + 1).setBody("small"),
        )

        assertEquals(
            SeriesReleaseSourceFailure.CLIENT,
            (service().search("年代記", 2025) as SeriesReleaseSourceResult.Failure).reason,
        )
        assertEquals(
            SeriesReleaseSourceFailure.PARSE,
            (service().search("年代記", 2025) as SeriesReleaseSourceResult.Failure).reason,
        )
        assertEquals(
            SeriesReleaseSourceFailure.PARSE,
            (service().search("年代記", 2025) as SeriesReleaseSourceResult.Failure).reason,
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun documentTypeIsRejectedWithoutResolvingExternalEntities() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """<!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><response>&xxe;</response>""",
            ),
        )

        val result = service().search("年代記", 2025) as SeriesReleaseSourceResult.Failure

        assertEquals(SeriesReleaseSourceFailure.PARSE, result.reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun productionEndpointIsFixedHttpsAndInvalidQueriesNeverReachNetwork() = runBlocking {
        val endpoint = buildNdlSeriesReleaseUrl("年代記", 2025)
        assertEquals("https", endpoint.scheme)
        assertEquals("ndlsearch.ndl.go.jp", endpoint.host)
        assertEquals("/api/sru", endpoint.encodedPath)

        val failure = service().search("\u0000", 2025) as SeriesReleaseSourceResult.Failure
        assertEquals(SeriesReleaseSourceFailure.CLIENT, failure.reason)
        assertEquals(0, server.requestCount)
        assertTrue(endpoint.queryParameter("query")!!.contains("dpid=\"open\""))
    }

    private fun service() = NdlSeriesReleaseService(
        callFactory = OkHttpClient.Builder().followRedirects(false).build(),
        baseUrl = server.url("/api/sru"),
    )

    private companion object {
        val RESPONSE = """
            <searchRetrieveResponse xmlns:dcndl="http://ndl.go.jp/dcndl/terms/"
              xmlns:dcterms="http://purl.org/dc/terms/"
              xmlns:dc="http://purl.org/dc/elements/1.1/"
              xmlns:foaf="http://xmlns.com/foaf/0.1/"
              xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <records><record><recordData><dcndl:BibResource rdf:about="https://id.ndl.go.jp/bib/1">
                <dcterms:title>年代記 完全版 3</dcterms:title>
                <dcterms:creator><foaf:Agent><foaf:name>山田太郎</foaf:name></foaf:Agent></dcterms:creator>
                <dcterms:publisher><foaf:Agent><foaf:name>出版社</foaf:name></foaf:Agent></dcterms:publisher>
                <dcterms:issued>2026-01</dcterms:issued><dc:identifier>ISBN9784820418078</dc:identifier>
              </dcndl:BibResource></recordData></record>
              <record><recordData><dcndl:BibResource rdf:about="https://id.ndl.go.jp/bib/2">
                <dcterms:title>年代記 完全版 4</dcterms:title><dc:creator>佐藤花子</dc:creator>
              </dcndl:BibResource></recordData></record></records>
            </searchRetrieveResponse>
        """.trimIndent()
    }
}
