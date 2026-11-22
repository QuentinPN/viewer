package io.github.quentinpn.viewer.tv.player

import android.content.Context

/**
 * The track a user last picked, by language and title: mpv numbers tracks per
 * file, so an id means nothing for the next one. [off] for subtitles turned off.
 */
data class TrackPreference(val off: Boolean, val lang: String?, val title: String?) {
    companion object {
        val OFF = TrackPreference(off = true, lang = null, title = null)

        fun of(track: Track) = TrackPreference(off = false, lang = track.lang, title = track.title)
    }
}

/** A track to select; [id] null turns the type off. */
data class Selection(val id: Int?)

/**
 * What [preference] asks for among [tracks]: the same language and title, else
 * the first track of that language. Null when nothing matches or it is already
 * selected: mpv's own choice then stays.
 */
fun choose(tracks: List<Track>, type: TrackType, preference: TrackPreference?): Selection? =
    remembered(tracks, type, preference)?.takeUnless { isSelected(tracks, type, it) }

/** The track [preference] designates among [tracks], selected or not; null when nothing matches. */
private fun remembered(tracks: List<Track>, type: TrackType, preference: TrackPreference?): Selection? {
    preference ?: return null
    if (preference.off) return Selection(null)
    val sameLanguage = tracks.filter { it.type == type && sameLanguage(it.lang, preference.lang) }
    val track = sameLanguage.firstOrNull { it.title.equals(preference.title, ignoreCase = true) }
        ?: sameLanguage.firstOrNull()
        ?: return null
    return Selection(track.id)
}

private fun isSelected(tracks: List<Track>, type: TrackType, selection: Selection): Boolean {
    val ofType = tracks.filter { it.type == type }
    return if (selection.id == null) ofType.none { it.selected } else ofType.any { it.id == selection.id && it.selected }
}

/** Where the track of a type comes from, as written in the `ViewerTV` log. */
enum class TrackOrigin(val log: String) {
    /** Picked in the track menu earlier. */
    Remembered("remembered"),

    /** `DefaultAudioStreamIndex` / `DefaultSubtitleStreamIndex` of PlaybackInfo. */
    Server("server"),

    /** The languages set in Réglages on this device, before the server's account-based pick. */
    Local("local"),

    /** The account's preferences applied by the client. */
    Account("account"),

    /** None of them: mpv's own choice (`slang`, the file's default flags). */
    Mpv("mpv"),
}

/**
 * The track to play for [type]: [selection] null leaves mpv's current track, which
 * is then already the one wanted. [embyIndex] is the server's pick when [origin] is
 * [TrackOrigin.Server].
 */
data class TrackDecision(val origin: TrackOrigin, val embyIndex: Int? = null, val selection: Selection? = null)

/**
 * The remembered choice first, then the server's pick [serverIndex], then
 * mpv's. As on Windows, when playing the file the Emby `Index` of one of
 * its own streams is the mpv track of the same type with that `ff-index`, external
 * files left out. A transcode carries the server's audio, and its subtitle pick was
 * added selected ([transcode]): nothing is left to select. When the server names no
 * track, or one not in the file, the account's pick [account], then mpv's.
 * A language set in Réglages on this device ([local]) comes right after the
 * remembered choice: the server's pick only follows the account.
 */
fun decide(
    tracks: List<Track>,
    type: TrackType,
    preference: TrackPreference?,
    serverIndex: Int?,
    transcode: Boolean,
    account: Selection? = null,
    local: Selection? = null,
): TrackDecision {
    remembered(tracks, type, preference)?.let { wanted ->
        return TrackDecision(TrackOrigin.Remembered, selection = wanted.takeUnless { isSelected(tracks, type, it) })
    }
    if (!transcode) {
        local?.let { wanted -> return TrackDecision(TrackOrigin.Local, selection = wanted.takeUnless { isSelected(tracks, type, it) }) }
    }
    if (serverIndex != null) {
        if (transcode) return TrackDecision(TrackOrigin.Server, serverIndex)
        tracks.firstOrNull { it.type == type && !it.external && it.ffIndex == serverIndex }?.let { track ->
            return TrackDecision(TrackOrigin.Server, serverIndex, Selection(track.id).takeUnless { track.selected })
        }
    }
    account?.let { wanted ->
        return TrackDecision(TrackOrigin.Account, selection = wanted.takeUnless { isSelected(tracks, type, it) })
    }
    return TrackDecision(TrackOrigin.Mpv)
}

/** "2→1 (server)", "-→no (remembered)": Emby index, mpv's track once applied, origin. No URL, no token. */
fun describeTrack(decision: TrackDecision, mpvTrack: String?): String =
    "${decision.embyIndex ?: "-"}→${mpvTrack ?: "?"} (${decision.origin.log})"

/** Kept per Emby user in plain preferences: a language and a track title are not secrets. */
class TrackPreferenceStore(context: Context, private val userId: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(type: TrackType): TrackPreference? {
        val key = key(type)
        if (!prefs.contains("$key.off")) return null
        return TrackPreference(prefs.getBoolean("$key.off", false), prefs.getString("$key.lang", null), prefs.getString("$key.title", null))
    }

    fun save(type: TrackType, preference: TrackPreference) {
        val key = key(type)
        prefs.edit()
            .putBoolean("$key.off", preference.off)
            .putString("$key.lang", preference.lang)
            .putString("$key.title", preference.title)
            .apply()
    }

    /** Forgets the pick of [type]: a language changed in Réglages applies from the next playback. */
    fun clear(type: TrackType) {
        val key = key(type)
        prefs.edit().remove("$key.off").remove("$key.lang").remove("$key.title").apply()
    }

    private fun key(type: TrackType) = "$userId.${type.mpv}"

    private companion object {
        const val PREFS = "tracks"
    }
}
