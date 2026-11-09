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
fun choose(tracks: List<Track>, type: TrackType, preference: TrackPreference?): Selection? {
    preference ?: return null
    val ofType = tracks.filter { it.type == type }
    if (preference.off) return if (ofType.any { it.selected }) Selection(null) else null
    val sameLanguage = ofType.filter { sameLanguage(it.lang, preference.lang) }
    val track = sameLanguage.firstOrNull { it.title.equals(preference.title, ignoreCase = true) }
        ?: sameLanguage.firstOrNull()
        ?: return null
    return if (track.selected) null else Selection(track.id)
}

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

    private fun key(type: TrackType) = "$userId.${type.mpv}"

    private companion object {
        const val PREFS = "tracks"
    }
}
