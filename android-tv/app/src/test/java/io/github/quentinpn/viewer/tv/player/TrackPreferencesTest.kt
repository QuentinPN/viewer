package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPreferencesTest {
    private val tracks = listOf(
        Track(1, TrackType.Audio, "", selected = true, lang = "fre"),
        Track(2, TrackType.Audio, "", selected = false, lang = "jpn"),
        Track(1, TrackType.Subtitle, "", selected = true, lang = "fre", title = "Forced"),
        Track(2, TrackType.Subtitle, "", selected = false, lang = "fre"),
        Track(3, TrackType.Subtitle, "", selected = false, lang = "fre", title = "CC"),
    )

    @Test
    fun languageAndTitleComeFirst() {
        assertEquals(Selection(3), choose(tracks, TrackType.Subtitle, TrackPreference(false, "fr", "cc")))
        // The same language with another title: the first track of it
        assertEquals(Selection(2), choose(tracks, TrackType.Audio, TrackPreference(false, "ja", "VO")))
        // No title remembered: the track without one
        assertEquals(Selection(2), choose(tracks, TrackType.Subtitle, TrackPreference(false, "fra", null)))
    }

    @Test
    fun subtitlesCanStayOff() {
        assertEquals(Selection(null), choose(tracks, TrackType.Subtitle, TrackPreference.OFF))
        assertNull(choose(tracks.map { it.copy(selected = false) }, TrackType.Subtitle, TrackPreference.OFF))
    }

    @Test
    fun mpvChoiceStaysWithoutAMatch() {
        assertNull(choose(tracks, TrackType.Audio, null))
        assertNull(choose(tracks, TrackType.Audio, TrackPreference(false, "eng", null)))
        // Already the one asked for
        assertNull(choose(tracks, TrackType.Audio, TrackPreference(false, "fr", null)))
    }

    @Test
    fun languageCodesAreCompared() {
        assertEquals(true, sameLanguage("fre", "fr"))
        assertEquals(true, sameLanguage("FRA", "fre"))
        assertEquals(true, sameLanguage(null, null))
        assertEquals(false, sameLanguage("fre", null))
        assertEquals(false, sameLanguage("ger", "eng"))
    }
}
