package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollingTest {
    @Test
    fun anItemOnScreenDoesNotScroll() {
        assertEquals(0f, scrollNeeded(offset = 200f, size = 300f, containerSize = 1080f), 0f)
        assertEquals(0f, scrollNeeded(offset = 0f, size = 1080f, containerSize = 1080f), 0f)
    }

    @Test
    fun anItemBelowScrollsJustEnoughToShowItsBottom() {
        assertEquals(120f, scrollNeeded(offset = 900f, size = 300f, containerSize = 1080f), 0f)
    }

    @Test
    fun anItemAboveScrollsBackToItsTop() {
        assertEquals(-50f, scrollNeeded(offset = -50f, size = 300f, containerSize = 1080f), 0f)
    }

    @Test
    fun anItemTallerThanTheScreenShowsFromItsStart() {
        assertEquals(400f, scrollNeeded(offset = 400f, size = 1500f, containerSize = 1080f), 0f)
    }
}
