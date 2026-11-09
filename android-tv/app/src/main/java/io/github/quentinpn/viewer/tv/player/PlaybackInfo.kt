package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.PlayMethod
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import kotlin.math.roundToLong

// The "Informations de lecture" panel (ADR 0061), as on Windows (ADR 0017, 0037):
// how the server delivers the item and what this device does with it. Every value
// comes from the PlaybackPlan or a libmpv property; a value mpv does not give is left out.

/** One line of the panel: a French label and its value, units included. */
data class InfoRow(val label: String, val value: String)

/** What the panel knows from the negotiation, kept by the player for the file being played. */
data class PlaybackFacts(
    val method: PlayMethod,
    /** French labels of `TranscodeReasons`, for a transcode only. */
    val reasons: List<String> = emptyList(),
    /** "Maximale · réseau local", "4 Mbit/s · accès distant". */
    val cap: String? = null,
)

/** libmpv properties the panel reads, as text, about once per second while it is open. */
internal val INFO_PROPERTIES = listOf(
    "file-format", "video-format", "video-params/w", "video-params/h", "container-fps",
    "video-params/gamma", "video-params/primaries", "video-params/hw-pixelformat", "video-params/pixelformat",
    "hwdec-current", "audio-codec-name", "audio-params/channel-count", "audio-params/samplerate",
    "current-ao", "audio-out-params/channel-count", "video-bitrate", "audio-bitrate",
    "decoder-frame-drop-count", "frame-drop-count", "demuxer-cache-duration",
)

/** The panel's rows; [get] reads one mpv property (null when mpv has none), [tracks] the file's audio and subtitle tracks. */
fun playbackInfo(facts: PlaybackFacts, tracks: List<Track>, get: (String) -> String?): List<InfoRow> {
    fun prop(name: String) = get(name)?.trim()?.takeIf { it.isNotEmpty() }
    fun number(name: String) = prop(name)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    fun whole(name: String) = number(name)?.toLong()
    val audioTrack = tracks.find { it.type == TrackType.Audio && it.selected }
    val subtitle = tracks.find { it.type == TrackType.Subtitle && it.selected }
    val rows = listOf(
        "Lecture" to methodLabel(facts.method, facts.reasons),
        "Débit maximal" to facts.cap,
        "Conteneur" to prop("file-format")?.let(::containerName),
        "Vidéo" to prop("video-format")?.let(::videoCodecName),
        "Résolution" to resolution(whole("video-params/w"), whole("video-params/h")),
        "Images par seconde" to number("container-fps")?.takeIf { it > 0 }?.let(::frameRate),
        "Couleur" to colour(prop("video-params/gamma"), prop("video-params/primaries"), bitDepth(prop("video-params/hw-pixelformat")) ?: bitDepth(prop("video-params/pixelformat"))),
        "Décodage" to prop("hwdec-current")?.let(::decoding),
        "Piste audio" to audioTrack?.label,
        "Format audio" to joined(
            prop("audio-codec-name")?.let(::codecName),
            whole("audio-params/channel-count")?.takeIf { it > 0 }?.let { channelLayout(it.toInt()) },
            whole("audio-params/samplerate")?.takeIf { it > 0 }?.let(::sampleRate),
        ),
        "Sortie audio" to joined(
            prop("current-ao")?.let(::audioOutputName),
            whole("audio-out-params/channel-count")?.takeIf { it > 0 }?.let { channelLayout(it.toInt()) },
        ),
        "Sous-titres" to if (tracks.isEmpty()) null else subtitle?.label ?: "Désactivés",
        "Débit mesuré" to bitrateOf(number("video-bitrate"), number("audio-bitrate")),
        "Images perdues" to droppedFrames(whole("decoder-frame-drop-count"), whole("frame-drop-count")),
        "Mémoire tampon" to number("demuxer-cache-duration")?.let(::bufferAhead),
    )
    return rows.mapNotNull { (label, value) -> value?.let { InfoRow(label, it) } }
}

private val METHOD_LABELS = mapOf(
    PlayMethod.DirectPlay to "Lecture directe",
    PlayMethod.DirectStream to "Flux direct",
    PlayMethod.Transcode to "Transcodage",
)

/** "Lecture directe", "Transcodage · débit trop élevé", as `playMethodLabel` on Windows. */
fun methodLabel(method: PlayMethod, reasons: List<String>): String {
    val label = METHOD_LABELS.getValue(method)
    if (method != PlayMethod.Transcode) return label
    return "$label · " + reasons.ifEmpty { listOf("raison non précisée") }.joinToString(", ")
}

/**
 * French labels of the `TranscodeReasons` parameter of a transcoding URL, as
 * `transcode_reasons` on Windows; a label is given once. Only that parameter is read:
 * the URL itself is never shown or logged.
 */
fun transcodeReasons(url: String): List<String> {
    val parsed = url.toHttpUrlOrNull() ?: return emptyList()
    return (0 until parsed.querySize)
        .filter { parsed.queryParameterName(it).equals("TranscodeReasons", ignoreCase = true) }
        .flatMap { parsed.queryParameterValue(it).orEmpty().split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::reasonLabel)
        .distinct()
}

internal fun reasonLabel(reason: String) = when (reason) {
    "ContainerNotSupported" -> "conteneur non pris en charge"
    "VideoCodecNotSupported" -> "codec vidéo non pris en charge"
    "AudioCodecNotSupported" -> "codec audio non pris en charge"
    "SubtitleCodecNotSupported" -> "sous-titres non pris en charge"
    "ContainerBitrateExceedsLimit", "VideoBitrateNotSupported" -> "débit trop élevé"
    "AudioChannelsNotSupported" -> "nombre de canaux audio non pris en charge"
    "DirectPlayError" -> "échec de la lecture directe"
    else -> "autre raison"
}

/** mpv's `file-format` (FFmpeg demuxer names) as a container name. */
internal fun containerName(format: String) = when (format.lowercase(Locale.ROOT)) {
    "matroska,webm", "matroska" -> "Matroska (MKV)"
    "webm" -> "WebM"
    "mov,mp4,m4a,3gp,3g2,mj2", "mp4", "mov" -> "MP4"
    "mpegts" -> "MPEG-TS"
    "hls", "applehttp" -> "HLS"
    "avi" -> "AVI"
    "asf" -> "ASF (WMV)"
    "mpeg" -> "MPEG-PS"
    else -> format.substringBefore(',').uppercase(Locale.ROOT)
}

internal fun videoCodecName(codec: String) = when (codec.lowercase(Locale.ROOT)) {
    "h264" -> "H.264 (AVC)"
    "hevc" -> "H.265 (HEVC)"
    "av1" -> "AV1"
    "vp8" -> "VP8"
    "vp9" -> "VP9"
    "mpeg2video" -> "MPEG-2"
    "mpeg4" -> "MPEG-4"
    "vc1" -> "VC-1"
    else -> codec.uppercase(Locale.ROOT)
}

/** "1920 × 1080 (1080p)"; null without both sides. */
internal fun resolution(width: Long?, height: Long?): String? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    val tier = when {
        width >= 3800 || height >= 2100 -> "4K"
        width >= 1900 || height >= 1060 -> "1080p"
        width >= 1260 || height >= 700 -> "720p"
        else -> "SD"
    }
    return "$width × $height ($tier)"
}

/** "23,976 i/s", "25 i/s": three decimals at most, trailing zeros removed. */
internal fun frameRate(fps: Double) = "${decimal(fps, 3)} i/s"

/** "HDR (PQ) · BT.2020 · 10 bits"; null when mpv gives none of them. */
internal fun colour(gamma: String?, primaries: String?, depth: Int?): String? {
    val range = when (gamma?.lowercase(Locale.ROOT)) {
        null, "auto" -> null
        "pq" -> "HDR (PQ)"
        "hlg" -> "HDR (HLG)"
        else -> "SDR"
    }
    val space = primaries?.takeUnless { it.equals("auto", ignoreCase = true) }?.uppercase(Locale.ROOT)
    return joined(range, space, depth?.let { "$it bits" })
}

/**
 * Bits per component of an FFmpeg pixel format: "yuv420p10le" 10, "p010" 10, "nv12" 8.
 * Null for a hardware surface ("mediacodec") or an unknown format: no guess.
 */
internal fun bitDepth(format: String?): Int? {
    val name = format?.lowercase(Locale.ROOT)?.removeSuffix("le")?.removeSuffix("be") ?: return null
    when (name) {
        "nv12", "nv21", "yuv420p", "yuvj420p", "yuv422p", "yuvj422p", "yuv444p", "yuvj444p", "rgb24", "bgr0", "rgba", "bgra" -> return 8
        "p010" -> return 10
        "p012" -> return 12
        "p016" -> return 16
    }
    // "yuv420p10", "yuv444p12": the digits after the last "p"
    if (name.startsWith("yuv") || name.startsWith("gbrp")) {
        val digits = name.substringAfterLast('p', "")
        if (digits.isNotEmpty() && digits.all { it.isDigit() }) return digits.toInt().takeIf { it in 8..16 }
    }
    return null
}

/** `hwdec-current`: "no" is software decoding. */
internal fun decoding(hwdec: String) =
    if (hwdec == "no") "Logiciel" else "Matériel ($hwdec)"

internal fun audioOutputName(ao: String) = when (ao) {
    "audiotrack" -> "AudioTrack"
    "opensles" -> "OpenSL ES"
    else -> ao
}

/** "48 kHz", "44,1 kHz". */
internal fun sampleRate(hz: Long) = "${decimal(hz / 1000.0, 1)} kHz"

/** "12,3 Mb/s", "640 kb/s": the video and audio bitrates mpv measures, added; null when it measures none. */
internal fun bitrateOf(video: Double?, audio: Double?): String? {
    if (video == null && audio == null) return null
    return bitrate((video ?: 0.0) + (audio ?: 0.0))
}

internal fun bitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 100_000_000 -> "${(bitsPerSecond / 1_000_000).roundToLong()} Mb/s"
    bitsPerSecond >= 1_000_000 -> "${decimal(bitsPerSecond / 1_000_000, 1)} Mb/s"
    else -> "${(bitsPerSecond / 1000).roundToLong()} kb/s"
}

/** "décodeur 0 · affichage 3"; null when mpv counts neither. */
internal fun droppedFrames(decoder: Long?, output: Long?): String? =
    joined(decoder?.let { "décodeur $it" }, output?.let { "affichage $it" })

/** "12 s d'avance": the demuxer cache ahead of the position. */
internal fun bufferAhead(seconds: Double) = "${decimal(seconds, if (seconds < 10) 1 else 0)} s d'avance"

/** [value] with at most [digits] decimals, a French comma and no trailing zeros. */
internal fun decimal(value: Double, digits: Int): String {
    val text = String.format(Locale.ROOT, "%.${digits}f", value)
    val trimmed = if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    return trimmed.replace('.', ',')
}

private fun joined(vararg parts: String?): String? =
    parts.filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
