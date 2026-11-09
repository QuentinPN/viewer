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

    /** Direct play of a film: French and English audio, French forced and full subtitles, English ones. */
    private val film = listOf(
        Track(1, TrackType.Audio, "", selected = true, lang = "fre", ffIndex = 1),
        Track(2, TrackType.Audio, "", selected = false, lang = "eng", ffIndex = 2),
        Track(1, TrackType.Subtitle, "", selected = true, lang = "fre", title = "Forced", ffIndex = 3),
        Track(2, TrackType.Subtitle, "", selected = false, lang = "fre", ffIndex = 4),
        Track(3, TrackType.Subtitle, "", selected = false, lang = "eng", ffIndex = 5),
        // An external file has its own ff-index numbering: never taken for an Emby index
        Track(4, TrackType.Subtitle, "", selected = false, lang = "spa", ffIndex = 0, external = true),
    )

    @Test
    fun theServerPickIsTheTrackWithTheSameFfIndex() {
        assertEquals(
            TrackDecision(TrackOrigin.Server, 2, Selection(2)),
            decide(film, TrackType.Audio, null, serverIndex = 2, transcode = false),
        )
        assertEquals(
            TrackDecision(TrackOrigin.Server, 4, Selection(2)),
            decide(film, TrackType.Subtitle, null, serverIndex = 4, transcode = false),
        )
        // Already the one mpv selected: nothing to change
        assertEquals(TrackDecision(TrackOrigin.Server, 1), decide(film, TrackType.Audio, null, 1, transcode = false))
    }

    @Test
    fun aRememberedChoiceWinsOverTheServer() {
        assertEquals(
            TrackDecision(TrackOrigin.Remembered, selection = Selection(3)),
            decide(film, TrackType.Subtitle, TrackPreference(false, "en", null), serverIndex = 4, transcode = false),
        )
        assertEquals(
            TrackDecision(TrackOrigin.Remembered, selection = Selection(null)),
            decide(film, TrackType.Subtitle, TrackPreference.OFF, serverIndex = 4, transcode = false),
        )
        // Remembered and already selected: still the remembered choice
        assertEquals(TrackDecision(TrackOrigin.Remembered), decide(film, TrackType.Audio, TrackPreference(false, "fra", null), 2, false))
        // Remembered language missing from this file: the server's pick, as on Windows
        assertEquals(
            TrackDecision(TrackOrigin.Server, 2, Selection(2)),
            decide(film, TrackType.Audio, TrackPreference(false, "jpn", null), serverIndex = 2, transcode = false),
        )
    }

    @Test
    fun mpvDecidesWhenTheServerPickCannotBeMapped() {
        assertEquals(TrackDecision(TrackOrigin.Mpv), decide(film, TrackType.Subtitle, null, serverIndex = null, transcode = false))
        // No embedded stream with that index (the server's own external file, a renumbered file)
        assertEquals(TrackDecision(TrackOrigin.Mpv), decide(film, TrackType.Subtitle, null, serverIndex = 9, transcode = false))
        assertEquals(TrackDecision(TrackOrigin.Mpv), decide(film, TrackType.Subtitle, null, serverIndex = 0, transcode = false))
        // An index of another type
        assertEquals(TrackDecision(TrackOrigin.Mpv), decide(film, TrackType.Audio, null, serverIndex = 4, transcode = false))
    }

    @Test
    fun aTranscodeAlreadyCarriesTheServerPick() {
        val transcode = listOf(
            Track(1, TrackType.Audio, "", selected = true, lang = "eng", ffIndex = 0),
            Track(1, TrackType.Subtitle, "", selected = true, lang = "fre", external = true),
        )
        assertEquals(TrackDecision(TrackOrigin.Server, 2), decide(transcode, TrackType.Audio, null, serverIndex = 2, transcode = true))
        assertEquals(TrackDecision(TrackOrigin.Server, 3), decide(transcode, TrackType.Subtitle, null, serverIndex = 3, transcode = true))
        assertEquals(
            TrackDecision(TrackOrigin.Remembered, selection = Selection(null)),
            decide(transcode, TrackType.Subtitle, TrackPreference.OFF, serverIndex = 3, transcode = true),
        )
    }

    @Test
    fun theDecisionIsLoggedWithoutAddresses() {
        assertEquals("2→2 (server)", describeTrack(TrackDecision(TrackOrigin.Server, 2, Selection(2)), "2"))
        assertEquals("-→no (remembered)", describeTrack(TrackDecision(TrackOrigin.Remembered, selection = Selection(null)), "no"))
        assertEquals("-→? (mpv)", describeTrack(TrackDecision(TrackOrigin.Mpv), null))
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
