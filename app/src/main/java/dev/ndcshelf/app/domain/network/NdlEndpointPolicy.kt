package dev.ndcshelf.app.domain.network

import java.net.URI
import java.util.Locale

object NdlEndpointPolicy {
    fun isAllowedCoverUrl(url: String, expectedIsbn13: String? = null): Boolean = runCatching {
        val uri = URI(url)
        val match = COVER_PATH.matchEntire(uri.rawPath.orEmpty()) ?: return@runCatching false
        uri.scheme?.lowercase(Locale.ROOT) == "https" &&
            uri.host?.lowercase(Locale.ROOT) == NDL_HOST &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (expectedIsbn13 == null || match.groupValues[1] == expectedIsbn13)
    }.getOrDefault(false)

    private const val NDL_HOST = "ndlsearch.ndl.go.jp"
    private val COVER_PATH = Regex("""/thumbnail/((?:978|979)\d{10})\.jpg""")
}
