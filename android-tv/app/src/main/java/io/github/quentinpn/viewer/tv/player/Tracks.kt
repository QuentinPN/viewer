package io.github.quentinpn.viewer.tv.player

import java.util.Locale

enum class TrackType(val mpv: String, val property: String) {
    Audio("audio", "aid"),
    Subtitle("sub", "sid"),
}

/**
 * One audio or subtitle track of the playing file, as mpv numbers it; [lang] and [title] as the file gives them.
 * [ffIndex] is the stream's number in its file: for the media's own streams, Emby's `Index` (ADR 0035, 0055).
 * [isDefault], [forced] and [hearingImpaired] are the file's flags, read for the account's preferences (ADR 0065).
 */
data class Track(
    val id: Int,
    val type: TrackType,
    val label: String,
    val selected: Boolean,
    val lang: String? = null,
    val title: String? = null,
    val ffIndex: Int? = null,
    val external: Boolean = false,
    val isDefault: Boolean = false,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
)

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
        val title = prop("title")
        val lang = prop("lang")
        val external = prop("external") == "yes"
        val label = trackLabel(
            type = type,
            title = title,
            lang = lang,
            codec = prop("codec"),
            channels = prop("demux-channel-count")?.toIntOrNull(),
            external = external,
        )
        Track(
            id, type, label, selected = prop("selected") == "yes", lang = lang, title = title,
            ffIndex = prop("ff-index")?.toIntOrNull(), external = external,
            isDefault = prop("default") == "yes", forced = prop("forced") == "yes",
            hearingImpaired = prop("hearing-impaired") == "yes",
        )
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

// ISO 639-2 codes (both forms) to ISO 639-1, for the languages met in the library
private val CODES = mapOf(
    "fre" to "fr", "fra" to "fr",
    "eng" to "en",
    "jpn" to "ja",
    "ger" to "de", "deu" to "de",
    "spa" to "es",
    "ita" to "it",
)

private val LANGUAGES = mapOf(
    "fr" to "Français",
    "en" to "Anglais",
    "ja" to "Japonais",
    "de" to "Allemand",
    "es" to "Espagnol",
    "it" to "Italien",
)

private fun canonical(code: String) = code.lowercase(Locale.ROOT).let { CODES[it] ?: it }

/** "fre", "fra" and "fr" are the same language; two unknown languages too. */
internal fun sameLanguage(a: String?, b: String?) = a?.let(::canonical) == b?.let(::canonical)

internal fun languageName(code: String): String {
    val lower = canonical(code)
    LANGUAGES[lower]?.let { return it }
    if (lower == "und") return "Langue inconnue"
    val name = Locale.forLanguageTag(lower).getDisplayLanguage(Locale.FRENCH)
    return if (name.isEmpty() || name.equals(lower, ignoreCase = true)) code.uppercase(Locale.ROOT)
    else name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

internal fun codecName(codec: String) = when (codec) {
    "subrip" -> "SRT"
    "hdmv_pgs_subtitle" -> "PGS"
    "dvd_subtitle" -> "VobSub"
    "mov_text" -> "TX3G"
    "eac3" -> "E-AC3"
    else -> codec.uppercase(Locale.ROOT)
}

internal fun channelLayout(channels: Int) = when (channels) {
    1 -> "mono"
    2 -> "stéréo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels canaux"
}
