package dev.ndcshelf.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.request.CachePolicy
import dev.ndcshelf.app.NDL_COVER_DISK_CACHE_BYTES
import dev.ndcshelf.app.createNdlCoverImageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookCoverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun ndlCoverRequestEnablesBoundedLocalCachesAndNetwork() {
        val request = buildNdlCoverImageRequest(context, ALLOWED_URL)

        requireNotNull(request)
        assertEquals(CachePolicy.ENABLED, request.memoryCachePolicy)
        assertEquals(CachePolicy.ENABLED, request.diskCachePolicy)
        assertEquals(CachePolicy.ENABLED, request.networkCachePolicy)
        assertTrue(request.data.toString().contains("ndlsearch.ndl.go.jp/thumbnail/"))
    }

    @Test
    fun externalCoverUrlDoesNotCreateANetworkRequest() {
        assertNull(buildNdlCoverImageRequest(context, "https://example.com/cover.jpg"))
    }

    @Test
    fun sharedImageLoaderBoundsTheRegenerableDiskCache() {
        val imageLoader = createNdlCoverImageLoader(context)
        try {
            assertEquals(NDL_COVER_DISK_CACHE_BYTES, imageLoader.diskCache?.maxSize)
        } finally {
            imageLoader.shutdown()
        }
    }

    private companion object {
        const val ALLOWED_URL =
            "https://ndlsearch.ndl.go.jp/thumbnail/9784820418078.jpg"
    }
}
