package io.github.quentinpn.viewer.tv.player

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The clock and « Fin à » of the controls (ADR 0073).

private const val MINUTE_MS = 60_000L

private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** « 21:05 »: [nowMs] since the epoch, 24 h, in [zone]. */
fun formatClock(nowMs: Long, zone: ZoneId): String = HOUR_MINUTE.format(Instant.ofEpochMilli(nowMs).atZone(zone))

/**
 * When playback ends if it plays on from [position], at [speed] (ADR 0072): what is left of
 * [duration] lasts that much less at a higher speed. Null while the duration is unknown (a
 * live stream, or before mpv gives it).
 */
fun endsAt(nowMs: Long, position: Double, duration: Double, speed: Double): Long? {
    if (!duration.isFinite() || duration <= 0) return null
    val left = (duration - position.coerceAtLeast(0.0)).coerceAtLeast(0.0) / offeredSpeed(speed)
    return nowMs + (left * 1_000).toLong()
}

/** « Fin à 22:47 », or null without a duration. */
fun endLabel(nowMs: Long, position: Double, duration: Double, speed: Double, zone: ZoneId): String? =
    endsAt(nowMs, position, duration, speed)?.let { "Fin à ${formatClock(it, zone)}" }

/** Milliseconds until the next minute of the clock starts; never zero, so a wait always moves on. */
fun untilNextMinute(nowMs: Long): Long = MINUTE_MS - Math.floorMod(nowMs, MINUTE_MS)
