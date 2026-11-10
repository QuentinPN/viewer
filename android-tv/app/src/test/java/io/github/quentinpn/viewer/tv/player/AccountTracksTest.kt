package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.UserConfigurationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountTracksTest {
    /** A Japanese anime with a French dub: forced and full French subtitles, English SDH ones. */
    private val anime = listOf(
        Track(1, TrackType.Audio, "", selected = true, lang = "jpn", isDefault = true),
        Track(2, TrackType.Audio, "", selected = false, lang = "fre"),
        Track(1, TrackType.Subtitle, "", selected = false, lang = "fre", title = "FR Forced"),
        Track(2, TrackType.Subtitle, "", selected = false, lang = "fre", isDefault = true),
        Track(3, TrackType.Subtitle, "", selected = false, lang = "eng", title = "English SDH"),
        Track(4, TrackType.Subtitle, "", selected = false, lang = "eng"),
    )

    private fun account(mode: SubtitleMode?, subtitles: List<String> = listOf("fre"), audio: List<String> = emptyList(), playDefault: Boolean = false) =
        AccountTracks(audio, playDefault, subtitles, mode)

    private fun subtitle(account: AccountTracks, audioLang: String? = "jpn", tracks: List<Track> = anime) =
        accountPick(tracks, TrackType.Subtitle, account, audioLang)

    @Test
    fun theConfigurationIsRead() {
        assertEquals(
            AccountTracks(listOf("jpn"), true, listOf("fre", "eng"), SubtitleMode.Smart),
            accountTracks(UserConfigurationDto("jpn", true, " fre, eng ,", "Smart")),
        )
        // Missing or unknown values: no preference
        assertEquals(AccountTracks(), accountTracks(UserConfigurationDto()))
        assertEquals(AccountTracks(subtitleMode = null), accountTracks(UserConfigurationDto(subtitleMode = "Sometimes")))
        assertEquals(SubtitleMode.OnlyForced, accountTracks(UserConfigurationDto(subtitleMode = "onlyforced")).subtitleMode)
        assertEquals(emptyList<String>(), languages("fr\ne"))
    }

    @Test
    fun audioFollowsTheLanguageThenTheFileDefault() {
        assertEquals(Selection(2), accountPick(anime, TrackType.Audio, account(null, audio = listOf("fra")), null))
        // The first wanted language present in the file
        assertEquals(Selection(1), accountPick(anime, TrackType.Audio, account(null, audio = listOf("ger", "jpn", "fre")), null))
        // PlayDefaultAudioTrack: the file's default track, whatever the language
        assertEquals(Selection(1), accountPick(anime, TrackType.Audio, account(null, audio = listOf("fre"), playDefault = true), null))
        // Asked for a default track the file does not flag: the language decides
        val noDefault = anime.map { it.copy(isDefault = false) }
        assertEquals(Selection(2), accountPick(noDefault, TrackType.Audio, account(null, audio = listOf("fre"), playDefault = true), null))
    }

    @Test
    fun audioWithoutAPreferenceOrAMatchAsksForNothing() {
        assertNull(accountPick(anime, TrackType.Audio, account(null), null))
        assertNull(accountPick(anime, TrackType.Audio, account(null, audio = listOf("spa")), null))
        assertNull(accountPick(anime, TrackType.Audio, null, null))
    }

    @Test
    fun noneTurnsSubtitlesOff() {
        assertEquals(Selection(null), subtitle(account(SubtitleMode.None)))
        assertEquals(Selection(null), subtitle(account(SubtitleMode.None, subtitles = emptyList())))
    }

    @Test
    fun onlyForcedTakesAForcedTrackOfAWantedLanguage() {
        // Forced by its title only, as on the Shield's "FR Forced"
        assertEquals(Selection(1), subtitle(account(SubtitleMode.OnlyForced)))
        val flagged = anime.map { if (it.type == TrackType.Subtitle && it.id == 4) it.copy(forced = true) else it }
        assertEquals(Selection(4), subtitle(account(SubtitleMode.OnlyForced, subtitles = listOf("eng")), tracks = flagged))
        // No wanted language: the audio's, then nothing of another dub
        assertEquals(Selection(1), subtitle(account(SubtitleMode.OnlyForced, subtitles = emptyList()), audioLang = "fre"))
        assertEquals(Selection(null), subtitle(account(SubtitleMode.OnlyForced, subtitles = emptyList()), audioLang = "jpn"))
        // No language known at all: any forced track
        assertEquals(Selection(1), subtitle(account(SubtitleMode.OnlyForced, subtitles = emptyList()), audioLang = null))
        // No forced track in the file: off
        assertEquals(Selection(null), subtitle(account(SubtitleMode.OnlyForced), tracks = anime.filterNot { it.id == 1 && it.type == TrackType.Subtitle }))
    }

    @Test
    fun defaultTakesAForcedTrackThenTheFileDefault() {
        assertEquals(Selection(1), subtitle(account(SubtitleMode.Default)))
        val withoutForced = anime.filterNot { it.type == TrackType.Subtitle && it.id == 1 }
        assertEquals(Selection(2), subtitle(account(SubtitleMode.Default), tracks = withoutForced))
        assertEquals(Selection(null), subtitle(account(SubtitleMode.Default), tracks = withoutForced.map { it.copy(isDefault = false) }))
    }

    @Test
    fun alwaysTakesAFullTrackOfAWantedLanguage() {
        // The full French track, not the forced one
        assertEquals(Selection(2), subtitle(account(SubtitleMode.Always)))
        // English: the plain track before the SDH one
        assertEquals(Selection(4), subtitle(account(SubtitleMode.Always, subtitles = listOf("eng"))))
        // Only a forced track in that language: better than nothing
        val forcedOnly = listOf(Track(5, TrackType.Subtitle, "", selected = false, lang = "fre", forced = true))
        assertEquals(Selection(5), subtitle(account(SubtitleMode.Always), tracks = forcedOnly))
    }

    @Test
    fun aLanguageWithoutATrackTurnsSubtitlesOff() {
        assertEquals(Selection(null), subtitle(account(SubtitleMode.Always, subtitles = listOf("spa"))))
        assertEquals(Selection(null), subtitle(account(SubtitleMode.HearingImpaired, subtitles = listOf("ger"))))
    }

    @Test
    fun aMissingPreferenceAsksForNothing() {
        assertNull(subtitle(account(null)))
        assertNull(subtitle(account(SubtitleMode.Always, subtitles = emptyList())))
        assertNull(subtitle(account(SubtitleMode.HearingImpaired, subtitles = emptyList())))
        assertNull(subtitle(account(SubtitleMode.Smart, subtitles = emptyList())))
        // Smart without the audio's language cannot tell a foreign dub
        assertNull(subtitle(account(SubtitleMode.Smart), audioLang = null))
    }

    @Test
    fun hearingImpairedComesFirstWhenAsked() {
        assertEquals(Selection(3), subtitle(account(SubtitleMode.HearingImpaired, subtitles = listOf("eng"))))
        // No such track: a full one of that language
        assertEquals(Selection(2), subtitle(account(SubtitleMode.HearingImpaired)))
    }

    @Test
    fun smartShowsSubtitlesForAForeignAudioOnly() {
        // Japanese audio, French wanted: the full French track
        assertEquals(Selection(2), subtitle(account(SubtitleMode.Smart), audioLang = "jpn"))
        // French audio: only its forced track
        assertEquals(Selection(1), subtitle(account(SubtitleMode.Smart), audioLang = "fra"))
        val withoutForced = anime.filterNot { it.type == TrackType.Subtitle && it.id == 1 }
        assertEquals(Selection(null), subtitle(account(SubtitleMode.Smart), audioLang = "fre", tracks = withoutForced))
    }

    @Test
    fun forcedAndHearingImpairedAreReadFromFlagsAndTitles() {
        fun sub(title: String?, forced: Boolean = false, hi: Boolean = false) =
            Track(1, TrackType.Subtitle, "", selected = false, title = title, forced = forced, hearingImpaired = hi)
        assertEquals(true, isForced(sub(null, forced = true)))
        assertEquals(true, isForced(sub("Français (forcés)")))
        assertEquals(false, isForced(sub("Français")))
        assertEquals(true, isHearingImpaired(sub("English SDH")))
        assertEquals(true, isHearingImpaired(sub(null, hi = true)))
        assertEquals(false, isHearingImpaired(sub("Sdhfoo")))
    }

    @Test
    fun theRememberedChoiceWinsOverTheAccount() {
        val wanted = subtitle(account(SubtitleMode.Always))
        assertEquals(
            TrackDecision(TrackOrigin.Remembered, selection = Selection(4)),
            decide(anime, TrackType.Subtitle, TrackPreference(false, "eng", null), serverIndex = null, transcode = false, account = wanted),
        )
        assertEquals(
            // Off remembered, and no subtitle selected yet: nothing to change
            TrackDecision(TrackOrigin.Remembered),
            decide(anime, TrackType.Subtitle, TrackPreference.OFF, serverIndex = null, transcode = false, account = wanted),
        )
    }

    @Test
    fun theAccountComesAfterTheServerPick() {
        val film = anime.mapIndexed { i, track -> track.copy(ffIndex = i + 1) }
        val wanted = subtitle(account(SubtitleMode.Always), tracks = film)
        // The server's pick found in the file: kept
        assertEquals(
            TrackDecision(TrackOrigin.Server, 6, Selection(4)),
            decide(film, TrackType.Subtitle, null, serverIndex = 6, transcode = false, account = wanted),
        )
        // No pick, or one not in the file: the account's
        assertEquals(
            TrackDecision(TrackOrigin.Account, selection = Selection(2)),
            decide(film, TrackType.Subtitle, null, serverIndex = null, transcode = false, account = wanted),
        )
        assertEquals(
            TrackDecision(TrackOrigin.Account, selection = Selection(2)),
            decide(film, TrackType.Subtitle, null, serverIndex = 42, transcode = false, account = wanted),
        )
        // Off and already off: nothing to change, but the account decided
        assertEquals(
            TrackDecision(TrackOrigin.Account),
            decide(film, TrackType.Subtitle, null, serverIndex = null, transcode = false, account = Selection(null)),
        )
        // Unreadable account: mpv, as before
        assertEquals(TrackDecision(TrackOrigin.Mpv), decide(film, TrackType.Subtitle, null, null, false, account = null))
        assertEquals("-→2 (account)", describeTrack(TrackDecision(TrackOrigin.Account, selection = Selection(2)), "2"))
    }

    @Test
    fun theAudioThatPlaysGivesItsLanguage() {
        assertEquals("fre", playingAudioLanguage(anime, TrackDecision(TrackOrigin.Account, selection = Selection(2))))
        assertEquals("jpn", playingAudioLanguage(anime, TrackDecision(TrackOrigin.Mpv)))
        assertNull(playingAudioLanguage(emptyList(), TrackDecision(TrackOrigin.Mpv)))
    }
}
