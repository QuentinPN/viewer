package io.github.quentinpn.viewer.tv.player

import kotlin.math.abs
import kotlin.math.roundToLong

// "Décalage des sous-titres" and "Décalage audio" of the track menu.

/** One press of ◀ or ▶. */
const val DELAY_STEP_SECONDS = 0.1

/** Either way: past it, the file itself is out of sync. */
const val DELAY_MAX_SECONDS = 10.0

private const val TENTHS = 10

private const val MAX_TENTHS = (DELAY_MAX_SECONDS * TENTHS).toLong()

/** mpv's property for each delay, in seconds: positive shows subtitles, or plays audio, later. */
enum class DelayKind(val property: String, val label: String) {
    Subtitle("sub-delay", "Décalage des sous-titres"),
    Audio("audio-delay", "Décalage audio"),
}

/** ◀ earlier, ▶ later, OK back to none. */
enum class DelayChange { Earlier, Later, Reset }

/** The delays of the current playback; a new file starts from none on both. */
data class Delays(val subtitle: Double = 0.0, val audio: Double = 0.0) {
    fun of(kind: DelayKind) = when (kind) {
        DelayKind.Subtitle -> subtitle
        DelayKind.Audio -> audio
    }

    fun with(kind: DelayKind, seconds: Double) = when (kind) {
        DelayKind.Subtitle -> copy(subtitle = seconds)
        DelayKind.Audio -> copy(audio = seconds)
    }
}

/** The delay after [change], on whole tenths so steps never drift, within ±[DELAY_MAX_SECONDS]. */
fun changeDelay(current: Double, change: DelayChange): Double {
    val tenths = when (change) {
        DelayChange.Reset -> return 0.0
        DelayChange.Earlier -> tenths(current) - 1
        DelayChange.Later -> tenths(current) + 1
    }
    return tenths.coerceIn(-MAX_TENTHS, MAX_TENTHS) / TENTHS.toDouble()
}

/** « +0,3 s », « −1,2 s » (real minus sign), « Aucun » once it rounds to zero. */
fun formatDelay(seconds: Double): String {
    val tenths = tenths(seconds)
    if (tenths == 0L) return "Aucun"
    val sign = if (tenths > 0) "+" else "−"
    val value = abs(tenths)
    return "$sign${value / TENTHS},${value % TENTHS} s"
}

// Non-finite values (never from mpv) count as none
private fun tenths(seconds: Double) = if (seconds.isFinite()) (seconds * TENTHS).roundToLong() else 0L
