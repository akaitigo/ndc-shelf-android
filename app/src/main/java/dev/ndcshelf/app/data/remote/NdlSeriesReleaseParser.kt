package dev.ndcshelf.app.data.remote

import dev.ndcshelf.app.scanner.Isbn
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

class NdlSeriesReleaseParser {
    fun parse(input: InputStream): List<SeriesReleaseSourceCandidate> {
        val handler = Handler()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            SECURITY_FEATURES.forEach { (feature, enabled) ->
                setFeature(feature, enabled)
            }
        }
        factory.newSAXParser().parse(input, handler)
        return handler.results
    }

    private class Handler : DefaultHandler() {
        private val names = mutableListOf<String>()
        private val text = mutableListOf<StringBuilder>()
        private var capturing = false
        private var sourceRecordId = ""
        private var title: String? = null
        private var publisher: String? = null
        private var issued: String? = null
        private val authors = linkedSetOf<String>()
        private val identifiers = linkedSetOf<String>()

        val results = mutableListOf<SeriesReleaseSourceCandidate>()

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader(""))

        override fun startElement(
            uri: String,
            localName: String,
            qName: String,
            attributes: Attributes,
        ) {
            val name = localName.ifBlank { qName.substringAfter(':') }
            names += name
            text += StringBuilder()
            if (name == "BibResource" && results.size < MAX_RESULTS) {
                capturing = true
                sourceRecordId = attributes.getValue(RDF_NAMESPACE, "about")
                    ?: attributes.getValue("rdf:about")
                    ?: ""
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val target = text.lastOrNull() ?: return
            if (target.length + length > MAX_FIELD_CHARACTERS) {
                throw SAXException("NDL field exceeds limit")
            }
            target.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val name = localName.ifBlank { qName.substringAfter(':') }
            val value = text.removeLastOrNull()?.toString()?.trim().orEmpty()
            val path = names.toList()
            if (capturing && value.isNotBlank()) {
                when {
                    name == "title" && uri == DCTERMS_NAMESPACE && title == null -> title = value
                    name == "name" && uri == FOAF_NAMESPACE && "creator" in path -> authors += value
                    name == "creator" && uri == DC_NAMESPACE && authors.isEmpty() -> authors += value
                    name == "name" && uri == FOAF_NAMESPACE && "publisher" in path &&
                        publisher == null -> publisher = value
                    name == "publisher" && uri == DC_NAMESPACE && publisher == null -> publisher = value
                    name == "issued" && uri == DCTERMS_NAMESPACE && issued == null -> issued = value
                    name == "identifier" -> identifiers += value
                }
            }
            if (name == "BibResource" && capturing) finishCandidate()
            names.removeLastOrNull()
        }

        private fun finishCandidate() {
            val parsedTitle = title?.takeIf(String::isNotBlank)
            if (parsedTitle != null) {
                val isbn = identifiers.asSequence()
                    .mapNotNull { ISBN_PATTERN.find(it)?.value }
                    .mapNotNull(Isbn::normalizeToIsbn13)
                    .firstOrNull()
                results += SeriesReleaseSourceCandidate(
                    sourceRecordId = sourceRecordId,
                    title = parsedTitle,
                    primaryAuthor = authors.joinToString("・").ifBlank { "著者不明" },
                    isbn13 = isbn,
                    publisher = publisher,
                    publishedDate = issued?.takeIf { DATE_PATTERN.matches(it) },
                )
            }
            capturing = false
            sourceRecordId = ""
            title = null
            publisher = null
            issued = null
            authors.clear()
            identifiers.clear()
        }
    }

    private companion object {
        const val MAX_RESULTS = 20
        const val MAX_FIELD_CHARACTERS = 16_384
        const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"
        const val DCTERMS_NAMESPACE = "http://purl.org/dc/terms/"
        const val FOAF_NAMESPACE = "http://xmlns.com/foaf/0.1/"
        const val RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val ISBN_PATTERN = Regex("(?:978|979)\\d{10}")
        val DATE_PATTERN = Regex("\\d{4}(?:-\\d{2}(?:-\\d{2})?)?")
        val SECURITY_FEATURES = mapOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )
    }
}
