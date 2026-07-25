package dev.ndcshelf.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NdlBookMetadataService(
    private val parser: NdlSruParser = NdlSruParser(),
) {
    suspend fun findByIsbn(isbn13: String): BookMetadata? = withContext(Dispatchers.IO) {
        val endpoint = buildNdlSruUrl(isbn13)
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/xml")
            connection.setRequestProperty("User-Agent", "NDC-Shelf/0.1.2 (Android)")

            if (connection.responseCode !in 200..299) {
                throw IOException("NDL Search returned HTTP ${connection.responseCode}")
            }

            connection.inputStream.buffered().use { stream ->
                parser.parse(stream, isbn13)
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun buildNdlSruUrl(isbn13: String): URL {
    val query = URLEncoder.encode("""isbn="$isbn13"""", Charsets.UTF_8.name())
    return URL(
        "$NDL_SRU_BASE_URL" +
            "?operation=searchRetrieve&version=1.2" +
            "&recordSchema=dcndl&recordPacking=xml&onlyBib=true" +
            "&maximumRecords=1&query=$query",
    )
}

private const val NDL_SRU_BASE_URL = "https://ndlsearch.ndl.go.jp/api/sru"
