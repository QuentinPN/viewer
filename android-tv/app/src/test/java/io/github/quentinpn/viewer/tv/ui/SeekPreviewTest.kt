package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPreviewTest {
    @Test
    fun previewIsCentredOnTheTargetAndKeptOnTheBar() {
        assertEquals(380f, previewStart(0.5f, 1000f, 240f), 0.001f)
        // Near the ends it stops at the bar's edges
        assertEquals(0f, previewStart(0.05f, 1000f, 240f), 0.001f)
        assertEquals(760f, previewStart(0.98f, 1000f, 240f), 0.001f)
        assertEquals(760f, previewStart(1.5f, 1000f, 240f), 0.001f)
        // A bar narrower than the preview: from its start
        assertEquals(0f, previewStart(0.5f, 100f, 240f), 0.001f)
    }
}
