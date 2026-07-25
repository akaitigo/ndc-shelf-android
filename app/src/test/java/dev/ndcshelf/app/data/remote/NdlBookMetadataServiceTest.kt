package dev.ndcshelf.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class NdlBookMetadataServiceTest {
    @Test
    fun `requests XML-packed DC-NDL records`() {
        val endpoint = buildNdlSruUrl("9784820418078")
        val decodedQuery = URLDecoder.decode(endpoint.query, Charsets.UTF_8.name())

        assertEquals("https", endpoint.protocol)
        assertEquals("ndlsearch.ndl.go.jp", endpoint.host)
        assertEquals("/api/sru", endpoint.path)
        assertTrue(decodedQuery.contains("operation=searchRetrieve"))
        assertTrue(decodedQuery.contains("recordSchema=dcndl"))
        assertTrue(decodedQuery.contains("recordPacking=xml"))
        assertTrue(decodedQuery.contains("onlyBib=true"))
        assertTrue(decodedQuery.contains("""query=isbn="9784820418078""""))
    }
}
