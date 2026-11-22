package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            "track-list/2/ff-index" to "3", "track-list/2/default" to "yes", "track-list/2/forced" to "yes",
            "track-list/2/hearing-impaired" to "no",
            "track-list/3/type" to "sub", "track-list/3/id" to "2", "track-list/3/lang" to "eng",
            "track-list/3/codec" to "subrip", "track-list/3/external" to "yes", "track-list/3/selected" to "yes",
            "track-list/3/hearing-impaired" to "yes",
        )
        assertEquals(
            listOf(
                Track(1, TrackType.Audio, "Japonais · AAC stéréo", selected = true, lang = "jpn", ffIndex = 1),
                Track(1, TrackType.Subtitle, "Français · ASS", selected = false, lang = "fre", ffIndex = 3, isDefault = true, forced = true),
                Track(2, TrackType.Subtitle, "Anglais · SRT (externe)", selected = true, lang = "eng", external = true, hearingImpaired = true),
            ),
            readTracks { props[it] },
        )
    }

    @Test
    fun aTranscodeSubtitleIsNamedByTheServerNotByItsFileName() {
        // The bug seen on the Shield: mpv titles an untitled file "Stream.ass"
        val url = "http://192.168.1.14:8096/emby/videos/1/m1/Subtitles/3/0/Stream.ass"
        fun sub(title: String?, lang: String?) = readTracks {
            mapOf(
                "track-list/count" to "1", "track-list/0/type" to "sub", "track-list/0/id" to "3", "track-list/0/codec" to "ass",
                "track-list/0/external" to "yes", "track-list/0/external-filename" to url,
                "track-list/0/title" to title, "track-list/0/lang" to lang,
            )[it]
        }.single()
        assertEquals("Français — Forced · ASS (externe)", sub("Forced", "fre").label)
        assertEquals("Français — CC · ASS (externe)", sub("CC", "fre").label)
        // No title from the server: the language alone, as for an untitled track of the file
        val untitled = sub("Stream.ass", "fre")
        assertEquals("Français · ASS (externe)", untitled.label)
        assertNull(untitled.title)
    }

    @Test
    fun onlyTheExternalFileNameIsDropped() {
        assertTrue(isFileName("Stream.ass", "http://h/emby/videos/1/Subtitles/3/0/Stream.ass"))
        assertTrue(isFileName("Stream.ass", "http://h/emby/videos/1/Subtitles/3/0/Stream.ass?a=1"))
        assertFalse(isFileName("Forced", "http://h/emby/videos/1/Subtitles/3/0/Stream.ass"))
        assertFalse(isFileName("Stream.ass", null))
        // A track of the file itself keeps its title, whatever it says
        val own = readTracks {
            mapOf(
                "track-list/count" to "1", "track-list/0/type" to "sub", "track-list/0/id" to "1",
                "track-list/0/title" to "Stream.ass", "track-list/0/external-filename" to "x/Stream.ass",
            )[it]
        }.single()
        assertEquals("Stream.ass", own.title)
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
