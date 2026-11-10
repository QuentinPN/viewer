package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModesTest {
    @Test
    fun speedsOffered() {
        assertEquals(listOf(0.75, 1.0, 1.25, 1.5, 2.0), SPEEDS)
        assertEquals("speed", SPEED_PROPERTY)
    }

    @Test
    fun speedStepsAndHoldsAtTheEnds() {
        assertEquals(1.25, changeSpeed(1.0, SpeedChange.Faster), 0.0)
        assertEquals(0.75, changeSpeed(1.0, SpeedChange.Slower), 0.0)
        assertEquals(0.75, changeSpeed(0.75, SpeedChange.Slower), 0.0)
        assertEquals(2.0, changeSpeed(2.0, SpeedChange.Faster), 0.0)
        assertEquals(1.0, changeSpeed(2.0, SpeedChange.Reset), 0.0)
        val up = generateSequence(0.75) { changeSpeed(it, SpeedChange.Faster).takeIf { next -> next != it } }.toList()
        assertEquals(SPEEDS, up)
    }

    @Test
    fun speedReadBackSnapsToTheNearestOffered() {
        // mpv may give a value slightly off
        assertEquals(1.5, offeredSpeed(1.4999999), 0.0)
        assertEquals(1.25, changeSpeed(1.0000001, SpeedChange.Faster), 0.0)
        // Off the list (set elsewhere): steps from the nearest one
        assertEquals(2.0, changeSpeed(1.6, SpeedChange.Faster), 0.0)
        for (bad in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) assertEquals("$bad", 1.0, offeredSpeed(bad), 0.0)
    }

    @Test
    fun speedLabelsAreFrench() {
        assertEquals("Normale", formatSpeed(1.0))
        assertEquals("0,75×", formatSpeed(0.75))
        assertEquals("1,25×", formatSpeed(1.25))
        assertEquals("1,5×", formatSpeed(1.5))
        assertEquals("2×", formatSpeed(2.0))
        assertNull("no badge at 1x", speedBadge(1.0))
        assertNull(speedBadge(1.0000001))
        assertEquals("1,5×", speedBadge(1.5))
        assertEquals("0,75×", speedBadge(0.75))
    }

    @Test
    fun nightModeFilter() {
        assertEquals("af", AUDIO_FILTER_PROPERTY)
        assertEquals("@night:lavfi=[acompressor=threshold=0.063:ratio=4:attack=20:release=250:makeup=2]", nightFilter(true))
        assertEquals("", nightFilter(false))
    }

    @Test
    fun nightModeHeldFromTheReadBack() {
        assertTrue(nightModeHeld(NIGHT_FILTER, true))
        // mpv may print the list in its own way: the compressor is what counts
        assertTrue(nightModeHeld("@night:lavfi=graph=\"acompressor=threshold=0.063\"", true))
        assertFalse("refused: af stayed empty", nightModeHeld("", true))
        assertFalse(nightModeHeld(null, true))
        assertTrue(nightModeHeld("", false))
        assertTrue(nightModeHeld(null, false))
        assertFalse("still filtering", nightModeHeld(NIGHT_FILTER, false))
    }

    @Test
    fun passthroughFromTheAudioFormat() {
        for (format in listOf("spdif-ac3", "spdif-eac3", "spdif-dts", "spdif-dtshd", "spdif-truehd", " spdif-ac3")) assertTrue(format, isPassthrough(format))
        for (format in listOf(null, "", "floatp", "s16", "float")) assertFalse("$format", isPassthrough(format))
    }

    @Test
    fun nightModeTexts() {
        assertEquals("Activé", nightValue(Night(on = true)))
        assertEquals("Désactivé", nightValue(Night()))
        assertEquals("Enregistré : s'appliquera à la prochaine lecture", nightNote(Night(true, NightState.NextPlayback)))
        assertTrue(nightNote(Night(true, NightState.Passthrough)).startsWith("Indisponible"))
        assertEquals(Night(on = false, state = NightState.Applied), Night())
    }
}
