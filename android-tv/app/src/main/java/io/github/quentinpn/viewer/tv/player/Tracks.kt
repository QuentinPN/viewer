package io.github.quentinpn.viewer.tv.player

import java.util.Locale

enum class TrackType(val mpv: String, val property: String) {
    Audio("audio", "aid"),
    Subtitle("sub", "sid"),
}

/** One audio or subtitle track of the playing file, as mpv numbers it. */
data class Track(val id: Int, val type: TrackType, val label: String, val selected: Boolean)

/**
 * Reads mpv's `track-list` through its sub-properties ([get] returns null for a
 * missing one). Video tracks are left out.
 */
fun readTracks(get: (String) -> String?): List<Track> {
    val count = get("track-list/count")?.toIntOrNull() ?: return emptyList()
    return (0 until count).mapNotNull { i ->
        fun prop(name: String) = get("track-list/$i/$name")?.takeIf { it.isNotEmpty() }
        val type = TrackType.entries.find { it.mpv == prop("type") } ?: return@mapNotNull null
        val id = prop("id")?.toIntOrNull() ?: return@mapNotNull null
        val label = trackLabel(
            type = type,
            title = prop("title"),
            lang = prop("lang"),
            codec = prop("codec"),
            channels = prop("demux-channel-count")?.toIntOrNull(),
            external = prop("external") == "yes",
        )
        Track(id, type, label, selected = prop("selected") == "yes")
    }
}

/** "Français · AC3 5.1", "Anglais · ASS (externe)"; the track title comes first when the file has one. */
internal fun trackLabel(type: TrackType, title: String?, lang: String?, codec: String?, channels: Int?, external: Boolean): String {
    val language = lang?.let(::languageName)
    val head = when {
        title != null && language != null && !title.contains(language, ignoreCase = true) -> "$language — $title"
        title != null -> title
        else -> language ?: "Piste inconnue"
    }
    val format = listOfNotNull(
        codec?.let(::codecName),
        if (type == TrackType.Audio) channels?.let(::channelLayout) else null,
    ).joinToString(" ")
    return buildString {
        append(head)
        if (format.isNotEmpty()) append(" · ").append(format)
        if (external) append(" (externe)")
    }
}

private val LANGUAGES = mapOf(
    "fr" to "Français", "fre" to "Français", "fra" to "Français",
    "en" to "Anglais", "eng" to "Anglais",
    "ja" to "Japonais", "jpn" to "Japonais",
    "de" to "Allemand", "ger" to "Allemand", "deu" to "Allemand",
    "es" to "Espagnol", "spa" to "Espagnol",
    "it" to "Italien", "ita" to "Italien",
)

internal fun languageName(code: String): String {
    val lower = code.lowercase(Locale.ROOT)
    LANGUAGES[lower]?.let { return it }
    if (lower == "und") return "Langue inconnue"
    val name = Locale.forLanguageTag(lower).getDisplayLanguage(Locale.FRENCH)
    return if (name.isEmpty() || name.equals(lower, ignoreCase = true)) code.uppercase(Locale.ROOT)
    else name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

private fun codecName(codec: String) = when (codec) {
    "subrip" -> "SRT"
    "hdmv_pgs_subtitle" -> "PGS"
    "dvd_subtitle" -> "VobSub"
    "mov_text" -> "TX3G"
    "eac3" -> "E-AC3"
    else -> codec.uppercase(Locale.ROOT)
}

private fun channelLayout(channels: Int) = when (channels) {
    1 -> "mono"
    2 -> "stéréo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels canaux"
}
