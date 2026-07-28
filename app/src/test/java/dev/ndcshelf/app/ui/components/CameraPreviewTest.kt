package dev.ndcshelf.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewTest {
    @Test
    fun zoomRatioIsClampedToCameraRange() {
        assertEquals(4f, calculateZoomRatio(2f, 3f, 1f, 4f), 0f)
        assertEquals(1f, calculateZoomRatio(2f, 0.1f, 1f, 4f), 0f)
        assertEquals(3f, calculateZoomRatio(2f, 1.5f, 1f, 4f), 0f)
    }

    @Test
    fun fixedZoomAndInvalidRangesAreSafe() {
        assertEquals(1f, calculateZoomRatio(1f, 2f, 1f, 1f), 0f)
        assertEquals(2f, calculateZoomRatio(2f, Float.NaN, 1f, 4f), 0f)
        assertEquals(2f, calculateZoomRatio(2f, 2f, 4f, 1f), 0f)
    }
}
