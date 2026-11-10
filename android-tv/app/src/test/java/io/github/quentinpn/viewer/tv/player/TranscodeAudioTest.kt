package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.PlayMethod
import io.github.quentinpn.viewer.tv.emby.PlaybackPlan
import io.github.quentinpn.viewer.tv.emby.ServerTracks
import io.github.quentinpn.viewer.tv.emby.SourceAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscodeAudioTest {
    // Overgeared S1E6: French first, Japanese second; the account prefers French
    private val audio = listOf(SourceAudio(1, "fre", null), SourceAudio(2, "jpn", null), SourceAudio(3, "jpn", "Commentaire"))

    private fun plan(method: PlayMethod, serverAudio: Int? = 1, source: String = "m1") =
        PlaybackPlan(method, "http://h/emby/videos/1/x", emptyMap(), source, "ps", null, emptyList(), ServerTracks(audio = serverAudio), audio)

    private fun audioTrack(id: Int, ffIndex: Int?, selected: Boolean, external: Boolean = false) =
        Track(id, TrackType.Audio, "a$id", selected, ffIndex = ffIndex, external = external)

    @Test
    fun theJapaneseHeardInDirectPlayIsAskedForAgain() {
        // The bug seen on the Shield: Japanese chosen, the quality change gave French
        val tracks = listOf(audioTrack(1, 1, selected = false), audioTrack(2, 2, selected = true))
        assertEquals(2, playingAudioIndex(plan(PlayMethod.DirectPlay), tracks))
        assertEquals(2, playingAudioIndex(plan(PlayMethod.DirectStream), tracks))
    }

    @Test
    fun aTranscodeKeepsTheAudioItCarries() {
        // HLS numbers its single audio 0 or 1: its ff-index is not an Emby Index
        val tracks = listOf(audioTrack(1, 0, selected = true))
        assertEquals(2, playingAudioIndex(plan(PlayMethod.Transcode, serverAudio = 2), tracks))
        assertNull(playingAudioIndex(plan(PlayMethod.Transcode, serverAudio = null), tracks))
    }

    @Test
    fun anUnknownOrExternalAudioIsNotAskedFor() {
        assertNull(playingAudioIndex(plan(PlayMethod.DirectPlay), listOf(audioTrack(1, 7, selected = true))))
        assertNull(playingAudioIndex(plan(PlayMethod.DirectPlay), listOf(audioTrack(1, 2, selected = true, external = true))))
        assertNull(playingAudioIndex(plan(PlayMethod.DirectPlay), listOf(audioTrack(1, 2, selected = false))))
        assertNull(playingAudioIndex(plan(PlayMethod.DirectPlay), listOf(audioTrack(1, null, selected = true))))
        assertNull(playingAudioIndex(plan(PlayMethod.DirectPlay), emptyList()))
    }

    @Test
    fun theRememberedLanguagePicksTheTranscodeAudio() {
        val japanese = TrackPreference(off = false, lang = "ja", title = null)
        assertEquals(2, rememberedAudioIndex(plan(PlayMethod.Transcode), japanese))
        // Same language and title first
        assertEquals(3, rememberedAudioIndex(plan(PlayMethod.Transcode), japanese.copy(title = "commentaire")))
        // An unknown title falls back on the language
        assertEquals(2, rememberedAudioIndex(plan(PlayMethod.Transcode), japanese.copy(title = "VO")))
    }

    @Test
    fun nothingToAskWhenTheServerAlreadyCarriesItOrNothingMatches() {
        val japanese = TrackPreference(off = false, lang = "jpn", title = null)
        assertNull(rememberedAudioIndex(plan(PlayMethod.Transcode, serverAudio = 2), japanese))
        assertNull(rememberedAudioIndex(plan(PlayMethod.Transcode), TrackPreference(off = false, lang = "ger", title = null)))
        assertNull(rememberedAudioIndex(plan(PlayMethod.Transcode), null))
        assertNull(rememberedAudioIndex(plan(PlayMethod.Transcode), TrackPreference.OFF))
        // The file played as is: the track menu picks among its own tracks
        assertNull(rememberedAudioIndex(plan(PlayMethod.DirectPlay), japanese))
        assertNull(rememberedAudioIndex(plan(PlayMethod.DirectStream), japanese))
    }

    @Test
    fun theSecondAnswerIsKeptOnlyAsATranscodeOfTheSameVersion() {
        val first = plan(PlayMethod.Transcode)
        assertTrue(keepsAudioRequest(first, plan(PlayMethod.Transcode, serverAudio = 2)))
        assertFalse(keepsAudioRequest(first, plan(PlayMethod.DirectPlay)))
        assertFalse(keepsAudioRequest(first, plan(PlayMethod.Transcode, source = "m2")))
    }
}
