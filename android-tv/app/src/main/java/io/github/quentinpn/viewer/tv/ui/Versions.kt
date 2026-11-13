package io.github.quentinpn.viewer.tv.ui

import android.content.Context
import androidx.core.content.edit
import io.github.quentinpn.viewer.tv.emby.MediaSourceDto
import io.github.quentinpn.viewer.tv.emby.SourceStreamDto
import io.github.quentinpn.viewer.tv.emby.isSourceId
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

// Version chooser of a film with several media sources (ADR 0058), ported from the
// Windows client (src/lib/detail/versions.ts and format.ts, ADR 0036): same labels,
// shown from two versions, the remembered one else the server's first.

/** One version offered on the film page; [id] is sent as PlaybackInfo's `MediaSourceId`. */
data class VersionChoice(val id: String, val label: String)

/** One choice per version with a usable id; none when there is nothing to choose. */
fun versionChoices(sources: List<MediaSourceDto>): List<VersionChoice> {
    val choices = sources.mapIndexedNotNull { i, source ->
        source.id?.takeIf(::isSourceId)?.let { VersionChoice(it, versionLabel(source, i)) }
    }
    return if (choices.size > 1) choices else emptyList()
}

/** The server's name first (`2160p — 4K · HEVC…`), else `Version 2 — …`. */
internal fun versionLabel(source: MediaSourceDto, index: Int): String {
    val name = source.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Version ${index + 1}"
    val details = versionDetails(source)
    return if (details.isEmpty()) name else "$name — $details"
}

/** `4K · HEVC 10 bits · HDR 10 · 27,9 Go`: size, else bitrate; SDR is not mentioned. */
internal fun versionDetails(source: MediaSourceDto): String {
    val video = source.mediaStreams.firstOrNull { it.type == "Video" }
    return listOfNotNull(
        video?.let(::videoLabel)?.ifEmpty { null },
        formatSize(source.size) ?: formatBitrate(source.bitrate),
    ).joinToString(" · ")
}

/** `4K · HEVC 10 bits · HDR 10`; SDR is not mentioned. */
internal fun videoLabel(video: SourceStreamDto): String {
    val depth = video.bitDepth?.takeIf { it > 8 }?.let { "$it bits" }
    return listOfNotNull(
        resolutionLabel(video),
        listOfNotNull(video.codec?.takeIf { it.isNotBlank() }?.let(::codecName), depth).joinToString(" ").ifEmpty { null },
        videoRange(video)?.takeIf { it != "SDR" },
    ).joinToString(" · ")
}

/** Usual name from the width: Emby files are often letterboxed (1920×800 is 1080p). */
private fun resolutionLabel(video: SourceStreamDto): String? {
    val width = video.width ?: 0
    return when {
        width >= 3800 -> "4K"
        width >= 2500 -> "1440p"
        width >= 1900 -> "1080p"
        width >= 1200 -> "720p"
        video.height != null && video.height > 0 -> "${video.height}p"
        else -> null
    }
}

private fun codecName(codec: String) = when (codec.lowercase(Locale.ROOT)) {
    "h264" -> "H.264"
    "hevc", "h265" -> "HEVC"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "mpeg2video" -> "MPEG-2"
    "mpeg4" -> "MPEG-4"
    "vc1" -> "VC-1"
    else -> codec.uppercase(Locale.ROOT)
}

/** `HDR 10`, `Dolby Vision Profile 8.1 (HDR10 compatible)`, as `video_range` on Windows. */
private fun videoRange(video: SourceStreamDto): String? {
    val range = video.videoRange?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (range != "DolbyVision") return range
    val detail = video.extendedVideoSubTypeDescription?.trim()?.takeIf { it.isNotEmpty() && it != "None" && it != range }
    return if (detail == null) "Dolby Vision" else "Dolby Vision $detail"
}

private fun decimal(value: Double): String =
    NumberFormat.getNumberInstance(Locale.FRANCE).apply {
        maximumFractionDigits = 1
        roundingMode = RoundingMode.HALF_UP
    }.format(value)

/** `3,5 Go`, binary units like Windows Explorer. */
internal fun formatSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val units = listOf("o", "Ko", "Mo", "Go", "To")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return "${decimal(value)} ${units[unit]}"
}

/** `4,9 Mb/s`. */
internal fun formatBitrate(bitsPerSecond: Long?): String? {
    if (bitsPerSecond == null || bitsPerSecond <= 0) return null
    return if (bitsPerSecond >= 1_000_000) "${decimal(bitsPerSecond / 1_000_000.0)} Mb/s"
    else "${Math.round(bitsPerSecond / 1000.0)} kb/s"
}

/** The remembered version if the server still offers it, else the first: the one it picks when asked for none. */
internal fun rememberedVersion(saved: String?, choices: List<VersionChoice>): String? =
    choices.firstOrNull { it.id == saved }?.id ?: choices.firstOrNull()?.id

/** The version after [id], then back to the first: OK on the film page's "Version" button. */
internal fun nextVersion(id: String?, choices: List<VersionChoice>): String? {
    if (choices.isEmpty()) return null
    val index = choices.indexOfFirst { it.id == id }
    return choices[(index + 1) % choices.size].id
}

/** The version chosen for each film, per Emby user, as `viewer.version.v1` on Windows. Ids only. */
class VersionStore(context: Context, private val userId: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(itemId: String, choices: List<VersionChoice>): String? = rememberedVersion(prefs.getString(key(itemId), null), choices)

    /** The server's default (first version) is not stored: the key is removed instead. */
    fun save(itemId: String, choices: List<VersionChoice>, id: String) {
        prefs.edit {
            if (id == choices.firstOrNull()?.id) remove(key(itemId)) else putString(key(itemId), id)
        }
    }

    private fun key(itemId: String) = "$userId.$itemId"

    private companion object {
        const val PREFS = "versions"
    }
}
