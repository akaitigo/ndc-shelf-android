package dev.ndcshelf.app.domain.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdlEndpointPolicyTest {
    @Test
    fun acceptsOnlyTheExpectedNdlHttpsThumbnailPath() {
        assertTrue(NdlEndpointPolicy.isAllowedCoverUrl(ALLOWED_URL, ISBN))
        assertTrue(NdlEndpointPolicy.isAllowedCoverUrl(ALLOWED_URL))

        listOf(
            "http://ndlsearch.ndl.go.jp/thumbnail/$ISBN.jpg",
            "https://example.com/thumbnail/$ISBN.jpg",
            "https://ndlsearch.ndl.go.jp.evil.example/thumbnail/$ISBN.jpg",
            "https://user@ndlsearch.ndl.go.jp/thumbnail/$ISBN.jpg",
            "https://ndlsearch.ndl.go.jp:444/thumbnail/$ISBN.jpg",
            "https://ndlsearch.ndl.go.jp/thumbnail/$ISBN.jpg?tracking=1",
            "https://ndlsearch.ndl.go.jp/other/$ISBN.jpg",
        ).forEach { assertFalse(it, NdlEndpointPolicy.isAllowedCoverUrl(it)) }

        assertFalse(NdlEndpointPolicy.isAllowedCoverUrl(ALLOWED_URL, "9784101010014"))
    }

    private companion object {
        const val ISBN = "9784820418078"
        const val ALLOWED_URL = "https://ndlsearch.ndl.go.jp/thumbnail/$ISBN.jpg"
    }
}
