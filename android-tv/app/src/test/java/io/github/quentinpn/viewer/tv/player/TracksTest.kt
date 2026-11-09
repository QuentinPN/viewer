package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TracksTest {
    @Test
    fun trackListIsReadFromSubProperties() {
        val props = mapOf(
            "track-list/count" to "4",
            "track-list/0/type" to "video", "track-list/0/id" to "1",
            "track-list/1/type" to "audio", "track-list/1/id" to "1", "track-list/1/lang" to "jpn",
            "track-list/1/codec" to "aac", "track-list/1/demux-channel-count" to "2", "track-list/1/selected" to "yes",
            "track-list/1/ff-index" to "1",
            "track-list/2/type" to "sub", "track-list/2/id" to "1", "track-list/2/lang" to "fre",
            "track-list/2/codec" to "ass", "track-list/2/title" to "", "track-list/2/selected" to "no",
            "track-list/2/ff-index" to "3",
            "track-list/3/type" to "sub", "track-list/3/id" to "2", "track-list/3/lang" to "eng",
            "track-list/3/codec" to "subrip", "track-list/3/external" to "yes", "track-list/3/selected" to "yes",
        )
        assertEquals(
            listOf(
                Track(1, TrackType.Audio, "Japonais · AAC stéréo", selected = true, lang = "jpn", ffIndex = 1),
                Track(1, TrackType.Subtitle, "Français · ASS", selected = false, lang = "fre", ffIndex = 3),
                Track(2, TrackType.Subtitle, "Anglais · SRT (externe)", selected = true, lang = "eng", external = true),
            ),
            readTracks { props[it] },
        )
    }

    @Test
    fun nothingLoadedMeansNoTracks() {
        assertTrue(readTracks { null }.isEmpty())
        assertTrue(readTracks { if (it == "track-list/count") "1" else null }.isEmpty())
    }

    @Test
    fun titleAndLanguageAreCombined() {
        assertEquals("Français — Forced · PGS", trackLabel(TrackType.Subtitle, "Forced", "fre", "hdmv_pgs_subtitle", null, false))
        assertEquals("Français VF · E-AC3 5.1", trackLabel(TrackType.Audio, "Français VF", "fra", "eac3", 6, false))
        assertEquals("Piste inconnue", trackLabel(TrackType.Subtitle, null, null, null, null, false))
        assertEquals("Langue inconnue · AC3 7.1", trackLabel(TrackType.Audio, null, "und", "ac3", 8, false))
    }
}
