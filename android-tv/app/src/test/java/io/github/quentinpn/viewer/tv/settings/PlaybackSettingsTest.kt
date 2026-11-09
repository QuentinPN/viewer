package io.github.quentinpn.viewer.tv.settings

import io.github.quentinpn.viewer.tv.emby.MAX_STREAMING_BITRATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSettingsTest {
    @Test
    fun defaultsAreThoseOfWindows() {
        val defaults = PlaybackSettings()
        assertTrue("external sources on", defaults.externalSegments)
        assertFalse("automatic skip off", defaults.autoSkip)
        assertNull(defaults.localCap)
        assertNull(defaults.remoteCap)
    }

    @Test
    fun choicesAreThoseOfWindows() {
        assertEquals(listOf(40_000_000L, 20_000_000L, 10_000_000L, 8_000_000L, 4_000_000L, 3_000_000L, 1_500_000L), BITRATE_CHOICES)
    }

    @Test
    fun capsOffTheListReadAsNoCap() {
        for (bad in listOf(0L, -1L, 1L, 3_500_000L, 400_000_000L, Long.MAX_VALUE)) assertNull("$bad", offeredCap(bad))
        assertEquals(4_000_000L, offeredCap(4_000_000))
        assertNull(offeredCap(null))
        val stored = PlaybackSettings(localCap = 123, remoteCap = 4_000_000).sanitized()
        assertEquals(PlaybackSettings(localCap = null, remoteCap = 4_000_000), stored)
    }

    @Test
    fun theCapOfTheAddressGoesToPlaybackInfo() {
        val settings = PlaybackSettings(localCap = 40_000_000, remoteCap = 1_500_000)
        assertEquals(40_000_000L, settings.maxStreamingBitrate(localAddress = true))
        assertEquals(1_500_000L, settings.maxStreamingBitrate(localAddress = false))
        assertEquals(MAX_STREAMING_BITRATE, PlaybackSettings().maxStreamingBitrate(localAddress = false))
        assertEquals(MAX_STREAMING_BITRATE, PlaybackSettings(remoteCap = 3_000_000).maxStreamingBitrate(localAddress = true))
    }

    @Test
    fun nextCapStepsThroughTheListAndBack() {
        // Null (back to "Maximale") ends the sequence
        val seen = generateSequence(nextCap(null)) { nextCap(it) }.toList()
        assertEquals(BITRATE_CHOICES, seen)
        assertNull(nextCap(1_500_000))
        assertEquals("unknown value restarts the list", 40_000_000L, nextCap(5_000_000))
    }

    @Test
    fun labelsAreFrench() {
        assertEquals("Maximale (automatique)", capLabel(null))
        assertEquals("40 Mbit/s", capLabel(40_000_000))
        assertEquals("1,5 Mbit/s", capLabel(1_500_000))
        assertEquals("8 Mbit/s", mbits(8_000_000))
        assertTrue(addressNote(true).contains("« Réseau local »"))
        assertTrue(addressNote(false).contains("« Accès distant »"))
    }
}
