package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.PlayMethod
import io.github.quentinpn.viewer.tv.emby.PlaybackPlan
import io.github.quentinpn.viewer.tv.emby.SourceAudio

// A transcode carries one audio stream, the one PlaybackInfo asks for with
// `AudioStreamIndex`, else the server's default. These pick the Emby
// `Index` to ask for, so the transcode keeps the audio the user listens to.

/**
 * Emby `Index` of the audio playing in [plan], to ask a new stream for the same one
 * when the quality changes. When playing the file, the selected track of
 * the file itself, by its `ff-index`; for a transcode, the audio the
 * server put in it. Null when unknown or not one of the source's audio streams.
 */
fun playingAudioIndex(plan: PlaybackPlan, tracks: List<Track>): Int? {
    val index = if (plan.method == PlayMethod.Transcode) {
        plan.serverTracks.audio
    } else {
        tracks.firstOrNull { it.type == TrackType.Audio && it.selected && !it.external }?.ffIndex
    }
    return index?.takeIf { i -> plan.audio.any { it.index == i } }
}

/**
 * The audio a transcode should carry at its first load, from the remembered choice:
 * same language and title, else the first of that language, as
 * [decide] does among mpv's tracks. Null when nothing is remembered, nothing matches,
 * the server already picked that track, or the file is played as is: the track menu
 * then chooses among the file's own tracks.
 */
fun rememberedAudioIndex(plan: PlaybackPlan, preference: TrackPreference?): Int? {
    if (plan.method != PlayMethod.Transcode || preference == null || preference.off) return null
    val index = matching(plan.audio, preference)?.index ?: return null
    return index.takeUnless { it == plan.serverTracks.audio }
}

private fun matching(audio: List<SourceAudio>, preference: TrackPreference): SourceAudio? {
    val sameLanguage = audio.filter { sameLanguage(it.language, preference.lang) }
    return sameLanguage.firstOrNull { it.title.equals(preference.title, ignoreCase = true) } ?: sameLanguage.firstOrNull()
}

/**
 * The stream asked for with `AudioStreamIndex` is kept only if it is still a transcode
 * of the same version: anything else falls back to the first answer.
 */
fun keepsAudioRequest(first: PlaybackPlan, second: PlaybackPlan) =
    second.method == PlayMethod.Transcode && second.mediaSourceId == first.mediaSourceId
