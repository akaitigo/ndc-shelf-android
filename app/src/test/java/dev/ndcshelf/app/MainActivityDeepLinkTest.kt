package dev.ndcshelf.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityDeepLinkTest {
    @Test
    fun mainActivityUsesSingleTopForLinksReceivedWhileOpen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test
    fun validBookLinkReturnsEditionId() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ndcshelf://book/edition-1"))

        assertEquals("edition-1", intent.bookDetailEditionId())
    }

    @Test
    fun unrelatedOrUnsafeLinksAreRejected() {
        assertNull(Intent(Intent.ACTION_SEND, Uri.parse("ndcshelf://book/edition-1")).bookDetailEditionId())
        assertNull(Intent(Intent.ACTION_VIEW, Uri.parse("https://book/edition-1")).bookDetailEditionId())
        assertNull(Intent(Intent.ACTION_VIEW, Uri.parse("ndcshelf://book/a/b")).bookDetailEditionId())
        assertNull(Intent(Intent.ACTION_VIEW, Uri.parse("ndcshelf://book/%2Fetc%2Fpasswd")).bookDetailEditionId())
        assertNull(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("ndcshelf://book/${"a".repeat(129)}"),
            ).bookDetailEditionId(),
        )
    }

    @Test
    fun consumedLinkIsNotReprocessedAfterStateRestoration() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ndcshelf://book/edition-1"))
        val consumedState = Bundle().apply {
            putString("requested-book-detail-edition-id", null)
        }

        assertNull(restoredBookDetailEditionId(consumedState, intent))
    }
}
