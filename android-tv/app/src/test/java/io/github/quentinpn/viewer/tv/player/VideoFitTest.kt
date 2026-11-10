package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFitTest {
    @Test
    fun fitsOfferedInMenuOrder() {
        assertEquals(listOf(VideoFit.Original, VideoFit.Fill, VideoFit.Stretch), VideoFit.entries)
        assertEquals("panscan", PANSCAN_PROPERTY)
        assertEquals("keepaspect", KEEPASPECT_PROPERTY)
    }

    @Test
    fun labelsAndBadges() {
        assertEquals("Original", VideoFit.Original.label)
        assertEquals("Remplir l'écran", VideoFit.Fill.label)
        assertEquals("Étirer", VideoFit.Stretch.label)
        assertNull(VideoFit.Original.badge)
        assertEquals("Remplir", VideoFit.Fill.badge)
        assertEquals("Étiré", VideoFit.Stretch.badge)
    }

    @Test
    fun fitStepsAndHoldsAtTheEnds() {
        assertEquals(VideoFit.Fill, changeFit(VideoFit.Original, FitChange.Next))
        assertEquals(VideoFit.Stretch, changeFit(VideoFit.Fill, FitChange.Next))
        assertEquals(VideoFit.Stretch, changeFit(VideoFit.Stretch, FitChange.Next))
        assertEquals(VideoFit.Fill, changeFit(VideoFit.Stretch, FitChange.Previous))
        assertEquals(VideoFit.Original, changeFit(VideoFit.Original, FitChange.Previous))
        for (fit in VideoFit.entries) assertEquals(VideoFit.Original, changeFit(fit, FitChange.Reset))
    }

    @Test
    fun eachFitSetsBothProperties() {
        assertEquals(listOf("panscan" to "0", "keepaspect" to "yes"), fitProperties(VideoFit.Original))
        assertEquals(listOf("panscan" to "1", "keepaspect" to "yes"), fitProperties(VideoFit.Fill))
        assertEquals(listOf("panscan" to "0", "keepaspect" to "no"), fitProperties(VideoFit.Stretch))
    }

    @Test
    fun readBackGivesTheFitSet() {
        // As mpv prints them: a double with six decimals, a flag as yes / no
        for (fit in VideoFit.entries) {
            val props = fitProperties(fit).toMap()
            assertEquals(fit, readFit(props[PANSCAN_PROPERTY], props[KEEPASPECT_PROPERTY]))
        }
        assertEquals(VideoFit.Original, readFit("0.000000", "yes"))
        assertEquals(VideoFit.Fill, readFit("1.000000", "yes"))
        assertEquals(VideoFit.Stretch, readFit("0.000000", "no"))
        assertEquals(VideoFit.Fill, readFit(" 1.000000 ", " yes "))
    }

    @Test
    fun unreadableOrPartialIsNoFit() {
        assertNull(readFit(null, "yes"))
        assertNull(readFit("0.000000", null))
        assertNull(readFit("", "yes"))
        assertNull(readFit("abc", "yes"))
        assertNull(readFit("0.500000", "yes"))
        assertNull(readFit("1.000000", "no"))
        assertNull(readFit("0.000000", "maybe"))
    }
}
