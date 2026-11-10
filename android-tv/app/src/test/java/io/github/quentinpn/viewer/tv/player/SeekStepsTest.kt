package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekStepsTest {
    @Test
    fun aPressAndTheFirstSecondOfRepeatsSeekTenSeconds() {
        assertEquals(10.0, seekStep(0), 0.0)
        assertEquals(10.0, seekStep(1), 0.0)
        assertEquals(10.0, seekStep(19), 0.0)
    }

    @Test
    fun theStepGrowsWhileTheKeyIsHeld() {
        assertEquals(30.0, seekStep(20), 0.0)
        assertEquals(30.0, seekStep(59), 0.0)
        assertEquals(60.0, seekStep(60), 0.0)
        assertEquals(60.0, seekStep(1_000), 0.0)
    }

    @Test
    fun aHoldSeeksOnEveryFourthRepeatOnly() {
        assertTrue("the press always seeks", seeksOnRepeat(0))
        assertFalse(seeksOnRepeat(1))
        assertFalse(seeksOnRepeat(3))
        assertTrue(seeksOnRepeat(4))
        assertFalse(seeksOnRepeat(5))
        assertTrue(seeksOnRepeat(20))
        // About 5 seeks a second at Android's 20 repeats a second
        assertEquals(5, (1..20).count(::seeksOnRepeat))
    }
}
