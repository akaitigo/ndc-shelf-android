package dev.ndcshelf.app.data.remote

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

class NdlSruParser {
    fun parse(input: InputStream, isbn13: String): BookMetadata? {
        val handler = NdlHandler(isbn13)
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            SECURITY_FEATURES.forEach { (feature, enabled) ->
                // Android's Expat parser and the JVM parser support slightly
                // different feature sets, so apply every supported safeguard.
                runCatching { setFeature(feature, enabled) }
            }
        }
        factory.newSAXParser().parse(input, handler)
        return handler.result
    }

    private class NdlHandler(
        private val isbn13: String,
    ) : DefaultHandler() {
        private val names = mutableListOf<String>()
        private val namespaces = mutableListOf<String>()
        private val text = mutableListOf<StringBuilder>()
        private val authors = linkedSetOf<String>()

        private var capturing = false
        private var title: String? = null
        private var publisher: String? = null
        private var issued: String? = null
        private var editionStatement: String? = null
        private var ndcCode: String? = null
        private var ndcEdition: String? = null

        var result: BookMetadata? = null
            private set

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
            namespaces += uri
            text += StringBuilder()

            if (name == "BibResource" && result == null) {
                capturing = true
            }

            if (capturing && name == "subject" && ndcCode == null) {
                val resource = attributes.getValue(RDF_NAMESPACE, "resource")
                    ?: attributes.getValue("rdf:resource")
                parseNdcResource(resource)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.lastOrNull()?.append(ch, start, length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            val name = localName.ifBlank { qName.substringAfter(':') }
            val value = text.removeLastOrNull()?.toString()?.trim().orEmpty()
            val path = names.toList()

            if (capturing && value.isNotBlank()) {
                when {
                    name == "title" && uri == DCTERMS_NAMESPACE && title == null -> {
                        title = value
                    }

                    name == "name" && uri == FOAF_NAMESPACE && "creator" in path -> {
                        authors += value
                    }

                    name == "creator" && uri == DC_NAMESPACE && authors.isEmpty() -> {
                        authors += value
                    }

                    name == "name" && uri == FOAF_NAMESPACE && "publisher" in path &&
                        publisher == null -> {
                        publisher = value
                    }

                    name == "publisher" && uri == DC_NAMESPACE && publisher == null -> {
                        publisher = value
                    }

                    name == "issued" && uri == DCTERMS_NAMESPACE && issued == null -> {
                        issued = value
                    }

                    name == "edition" && editionStatement == null -> {
                        editionStatement = value
                    }

                    name == "subject" && uri == DCTERMS_NAMESPACE && ndcCode == null -> {
                        NDC_CODE_REGEX.matchEntire(value)?.value?.let { ndcCode = it }
                    }
                }
            }

            if (name == "BibResource" && capturing) {
                capturing = false
                result = title?.takeIf(String::isNotBlank)?.let { parsedTitle ->
                    BookMetadata(
                        title = parsedTitle,
                        authors = authors.toList(),
                        publisher = publisher,
                        publishedYear = issued
                            ?.let { YEAR_REGEX.find(it)?.value }
                            ?.toIntOrNull(),
                        editionStatement = editionStatement,
                        ndcCode = ndcCode,
                        ndcEdition = ndcEdition,
                        coverUrl = "https://ndlsearch.ndl.go.jp/thumbnail/$isbn13.jpg",
                    )
                }
            }

            if (text.isNotEmpty() && value.isNotBlank()) {
                if (text.last().isNotEmpty()) text.last().append(' ')
                text.last().append(value)
            }
            names.removeLastOrNull()
            namespaces.removeLastOrNull()
        }

        private fun parseNdcResource(resource: String?) {
            if (resource.isNullOrBlank()) return
            val match = NDC_RESOURCE_REGEX.find(resource) ?: return
            ndcEdition = "NDC${match.groupValues[1]}"
            ndcCode = match.groupValues[2]
        }

        companion object {
            private const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"
            private const val DCTERMS_NAMESPACE = "http://purl.org/dc/terms/"
            private const val FOAF_NAMESPACE = "http://xmlns.com/foaf/0.1/"
            private const val RDF_NAMESPACE =
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

            private val YEAR_REGEX = Regex("""\d{4}""")
            private val NDC_CODE_REGEX = Regex("""\d{3}(?:\.\d+)?""")
            private val NDC_RESOURCE_REGEX =
                Regex("""/class/ndc(\d+)/(\d{3}(?:\.\d+)?)""")
        }
    }

    private companion object {
        val SECURITY_FEATURES = mapOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )
    }
}
