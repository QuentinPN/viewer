package io.github.quentinpn.viewer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageOverridesTest {
    private fun audio(id: Int, lang: String, default: Boolean = false) =
        Track(id = id, type = TrackType.Audio, label = lang, selected = default, lang = lang, ffIndex = id - 1, isDefault = default)

    private fun sub(id: Int, lang: String, forced: Boolean = false) =
        Track(id = id, type = TrackType.Subtitle, label = lang, selected = false, lang = lang, forced = forced)

    private val tracks = listOf(audio(1, "eng", default = true), audio(2, "fre"), sub(1, "fre"), sub(2, "eng"), sub(3, "fre", forced = true))
    private val account = AccountTracks(audioLanguages = listOf("eng"), playDefaultAudio = true, subtitleLanguages = listOf("eng"), subtitleMode = SubtitleMode.Always)

    @Test
    fun `nothing set here leaves the account alone`() {
        assertNull(localTracks(account, LanguageOverrides(), TrackType.Audio))
        assertNull(localPick(tracks, TrackType.Subtitle, account, LanguageOverrides(), "eng", transcode = false))
    }

    @Test
    fun `a local audio language comes before the account's and beats the file default`() {
        val local = localTracks(account, LanguageOverrides(audioLanguage = "fre"), TrackType.Audio)!!
        assertEquals(listOf("fre", "eng"), local.audioLanguages)
        assertEquals(Selection(2), localPick(tracks, TrackType.Audio, account, LanguageOverrides(audioLanguage = "fre"), null, transcode = false))
    }

    @Test
    fun `the same language in another spelling is kept once`() {
        val local = localTracks(AccountTracks(audioLanguages = listOf("fra", "eng")), LanguageOverrides(audioLanguage = "fre"), TrackType.Audio)!!
        assertEquals(listOf("fre", "eng"), local.audioLanguages)
    }

    @Test
    fun `a local subtitle mode replaces the account's`() {
        assertEquals(Selection(null), localPick(tracks, TrackType.Subtitle, account, LanguageOverrides(subtitleMode = SubtitleMode.None), "eng", transcode = false))
        assertEquals(Selection(3), localPick(tracks, TrackType.Subtitle, account, LanguageOverrides(subtitleMode = SubtitleMode.OnlyForced, subtitleLanguage = "fre"), "fre", transcode = false))
    }

    @Test
    fun `a local subtitle language keeps the account's mode`() {
        assertEquals(Selection(1), localPick(tracks, TrackType.Subtitle, account, LanguageOverrides(subtitleLanguage = "fre"), "eng", transcode = false))
    }

    @Test
    fun `works before the account is read, with Emby's default mode`() {
        val local = localTracks(null, LanguageOverrides(subtitleLanguage = "fre"), TrackType.Subtitle)!!
        assertEquals(SubtitleMode.Default, local.subtitleMode)
        assertEquals(Selection(2), localPick(tracks, TrackType.Audio, null, LanguageOverrides(audioLanguage = "fre"), null, false))
    }

    @Test
    fun `a transcode keeps the server's tracks`() {
        assertNull(localPick(tracks, TrackType.Audio, account, LanguageOverrides(audioLanguage = "fre"), null, transcode = true))
    }

    @Test
    fun `the local pick comes after the remembered one and before the server's`() {
        val local = Selection(2)
        assertEquals(TrackOrigin.Local, decide(tracks, TrackType.Audio, null, serverIndex = 0, transcode = false, account = null, local = local).origin)
        val remembered = TrackPreference(off = false, lang = "eng", title = null)
        assertEquals(TrackOrigin.Remembered, decide(tracks, TrackType.Audio, remembered, serverIndex = 0, transcode = false, local = local).origin)
    }

    @Test
    fun `choices cycle from the account through the list and back`() {
        assertEquals("fre", nextOverride(null, LANGUAGE_CHOICES))
        assertEquals("eng", nextOverride("fre", LANGUAGE_CHOICES))
        assertNull(nextOverride(LANGUAGE_CHOICES.last(), LANGUAGE_CHOICES))
        assertEquals("Comme le compte Emby (Anglais)", accountLabel(listOf("Anglais")))
        assertEquals("Comme le compte Emby (non défini)", accountLabel(emptyList()))
    }
}
