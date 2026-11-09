package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DelayTest {
    @Test
    fun stepsByATenth() {
        assertEquals(0.1, changeDelay(0.0, DelayChange.Later), 0.0)
        assertEquals(-0.1, changeDelay(0.0, DelayChange.Earlier), 0.0)
        assertEquals(1.3, changeDelay(1.2, DelayChange.Later), 0.0)
        // Thirty presses land on 3 exactly: no floating drift
        val after = (1..30).fold(0.0) { delay, _ -> changeDelay(delay, DelayChange.Later) }
        assertEquals(3.0, after, 0.0)
        // A value mpv gives back slightly off still steps from its tenth
        assertEquals(0.4, changeDelay(0.30000000000000004, DelayChange.Later), 0.0)
    }

    @Test
    fun clampsToTenSeconds() {
        assertEquals(10.0, changeDelay(10.0, DelayChange.Later), 0.0)
        assertEquals(-10.0, changeDelay(-10.0, DelayChange.Earlier), 0.0)
        assertEquals(10.0, changeDelay(42.0, DelayChange.Earlier), 0.0)
        assertEquals(9.9, changeDelay(10.0, DelayChange.Earlier), 0.0)
    }

    @Test
    fun resetGoesBackToNone() {
        assertEquals(0.0, changeDelay(-4.7, DelayChange.Reset), 0.0)
        assertEquals(0.0, changeDelay(Double.NaN, DelayChange.Reset), 0.0)
        assertEquals(0.1, changeDelay(Double.NaN, DelayChange.Later), 0.0)
    }

    @Test
    fun formatsInFrench() {
        assertEquals("Aucun", formatDelay(0.0))
        assertEquals("Aucun", formatDelay(-0.0))
        assertEquals("Aucun", formatDelay(0.04))
        assertEquals("+0,3 s", formatDelay(0.3))
        assertEquals("−1,2 s", formatDelay(-1.2))
        assertEquals("+10,0 s", formatDelay(10.0))
        assertEquals("−0,1 s", formatDelay(-0.1))
        assertEquals("+2,0 s", formatDelay(1.96))
    }

    @Test
    fun delaysOfThePlayback() {
        val shifted = Delays().with(DelayKind.Subtitle, 1.5).with(DelayKind.Audio, -0.2)
        assertEquals(1.5, shifted.of(DelayKind.Subtitle), 0.0)
        assertEquals(-0.2, shifted.of(DelayKind.Audio), 0.0)
        // A new file starts from none on both
        assertEquals(0.0, Delays().of(DelayKind.Subtitle), 0.0)
        assertEquals(0.0, Delays().of(DelayKind.Audio), 0.0)
        assertEquals(listOf("sub-delay", "audio-delay"), DelayKind.entries.map { it.property })
    }
}
