package io.github.quentinpn.viewer.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationLabelsTest {
    private val second = 10_000_000L

    @Test
    fun lengthIsInMinutesThenHours() {
        assertEquals("0 min", length(0))
        assertEquals("48 min", length(48 * 60 * second + 59 * second))
        assertEquals("1 h 00", length(60 * 60 * second))
        assertEquals("1 h 52", length(112 * 60 * second))
    }

    @Test
    fun clockShowsHoursOnlyWhenNeeded() {
        assertEquals("0:00", clock(0))
        assertEquals("46:07", clock((46 * 60 + 7) * second))
        assertEquals("1:02:05", clock((3600 + 2 * 60 + 5) * second))
    }
}
