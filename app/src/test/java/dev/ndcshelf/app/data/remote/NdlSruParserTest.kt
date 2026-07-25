package dev.ndcshelf.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NdlSruParserTest {
    private val parser = NdlSruParser()

    @Test
    fun `parses first NDL bibliographic resource`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <searchRetrieveResponse
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:dcterms="http://purl.org/dc/terms/"
                xmlns:dcndl="http://ndl.go.jp/dcndl/terms/"
                xmlns:foaf="http://xmlns.com/foaf/0.1/"
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <records>
                    <record>
                        <recordData>
                            <dcndl:BibResource>
                                <dcterms:title>図書館の本</dcterms:title>
                                <dcterms:creator>
                                    <foaf:Agent><foaf:name>山田太郎</foaf:name></foaf:Agent>
                                </dcterms:creator>
                                <dcterms:publisher>
                                    <foaf:Agent><foaf:name>サンプル出版</foaf:name></foaf:Agent>
                                </dcterms:publisher>
                                <dcterms:issued>2024-03</dcterms:issued>
                                <dcterms:subject
                                    rdf:resource="http://id.ndl.go.jp/class/ndc10/014.45" />
                            </dcndl:BibResource>
                        </recordData>
                    </record>
                </records>
            </searchRetrieveResponse>
        """.trimIndent()

        val result = parser.parse(
            input = xml.byteInputStream(),
            isbn13 = "9784820418078",
        )

        requireNotNull(result)
        assertEquals("図書館の本", result.title)
        assertEquals(listOf("山田太郎"), result.authors)
        assertEquals("サンプル出版", result.publisher)
        assertEquals(2024, result.publishedYear)
        assertEquals("014.45", result.ndcCode)
        assertEquals("NDC10", result.ndcEdition)
        assertEquals(
            "https://ndlsearch.ndl.go.jp/thumbnail/9784820418078.jpg",
            result.coverUrl,
        )
    }

    @Test
    fun `returns null when response contains no book`() {
        val xml = """
            <searchRetrieveResponse>
                <numberOfRecords>0</numberOfRecords>
            </searchRetrieveResponse>
        """.trimIndent()

        assertNull(parser.parse(xml.byteInputStream(), "9784820418078"))
    }
}
