package io.github.quentinpn.viewer.tv.player

import kotlin.math.abs
import kotlin.math.roundToInt

// "Vitesse" and "Mode nuit" of the track menu.

/** mpv's playback speed, a factor; pitch stays put through `audio-pitch-correction` (scaletempo2). */
const val SPEED_PROPERTY = "speed"

/** Speeds offered, slowest first; 1 is « Normale ». */
val SPEEDS: List<Double> = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

const val NORMAL_SPEED = 1.0

/** ◀ slower, ▶ faster, OK back to « Normale ». */
enum class SpeedChange { Slower, Faster, Reset }

/** The offered speed nearest [speed]; anything unreadable is « Normale ». */
fun offeredSpeed(speed: Double?): Double {
    if (speed == null || !speed.isFinite() || speed <= 0) return NORMAL_SPEED
    return SPEEDS.minBy { abs(it - speed) }
}

/** The speed after [change], from the offered one nearest [current], held at both ends of the list. */
fun changeSpeed(current: Double, change: SpeedChange): Double {
    val index = SPEEDS.indexOf(offeredSpeed(current))
    return when (change) {
        SpeedChange.Reset -> NORMAL_SPEED
        SpeedChange.Slower -> SPEEDS[(index - 1).coerceAtLeast(0)]
        SpeedChange.Faster -> SPEEDS[(index + 1).coerceAtMost(SPEEDS.lastIndex)]
    }
}

/** « 0,75× », « 1,5× », « 2× »: up to two decimals, French comma. */
private fun factor(speed: Double): String {
    val hundredths = (speed * 100).roundToInt()
    val whole = hundredths / 100
    val decimals = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (decimals.isEmpty()) "$whole×" else "$whole,$decimals×"
}

/** The menu's value: « Normale » at 1×. */
fun formatSpeed(speed: Double): String = offeredSpeed(speed).let { if (it == NORMAL_SPEED) "Normale" else factor(it) }

/** Shown in the controls while the speed is not 1×; null at « Normale ». */
fun speedBadge(speed: Double): String? = offeredSpeed(speed).takeIf { it != NORMAL_SPEED }?.let(::factor)

/** mpv's audio filter chain: night mode is its only filter. */
const val AUDIO_FILTER_PROPERTY = "af"

/**
 * Night mode: FFmpeg's `acompressor` through mpv's `lavfi` wrapper, labelled so it can be
 * told apart. Above about −24 dBFS (threshold 0.063, linear) peaks are compressed 4:1; a
 * makeup gain of 2 (about +6 dB) then lifts everything, so speech below the threshold
 * gains 6 dB while explosions lose up to about 12 dB. Short attack and release, no
 * look-ahead: no added latency, unlike `dynaudnorm`. Linear values, no "dB" suffix.
 */
const val NIGHT_FILTER = "@night:lavfi=[acompressor=threshold=0.063:ratio=4:attack=20:release=250:makeup=2]"

/** The `af` value for night mode on or off. */
fun nightFilter(on: Boolean): String = if (on) NIGHT_FILTER else ""

/** Whether mpv holds night mode as asked, from `af` read back: the compressor is in it, or not. */
fun nightModeHeld(af: String?, on: Boolean): Boolean = (af?.contains("acompressor") == true) == on

/** Audio formats mpv reports while it passes audio through undecoded: `spdif-ac3`, `spdif-eac3`… */
fun isPassthrough(format: String?): Boolean = format?.trim()?.startsWith("spdif") == true

/** Where night mode stands for the file being played. */
enum class NightState {
    /** As set: on and filtering, or off. */
    Applied,

    /** mpv refused the change during playback: it is saved and applies from the next one. */
    NextPlayback,

    /** Audio goes out undecoded to the amplifier: no filter can touch it. */
    Passthrough,
}

/** Night mode as the player shows it. */
data class Night(val on: Boolean = false, val state: NightState = NightState.Applied)

/** The menu's value. */
fun nightValue(night: Night): String = if (night.on) "Activé" else "Désactivé"

/** The line under « Mode nuit » in the menu. */
fun nightNote(night: Night): String = when (night.state) {
    NightState.Applied -> "Réduit l'écart entre dialogues et explosions"
    NightState.NextPlayback -> "Enregistré : s'appliquera à la prochaine lecture"
    NightState.Passthrough -> "Indisponible : le son part tel quel vers l'ampli"
}
