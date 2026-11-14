package io.github.quentinpn.viewer.tv.sync

import kotlin.math.abs

// The TV's correction toward the leader (ADR 0124 § Recalage de la TV). Every time comes
// from the TV's own monotonic clock: the two devices' clocks are never compared.

/** Round trips kept for [rttMs]. */
const val RTT_SAMPLES = 5

/** « Décalage TV »: 0 to 1500 ms by 100 ms. */
const val MAX_OFFSET_MS = 1_500
const val OFFSET_STEP_MS = 100

/** How often the decision is evaluated, besides at each `state`. */
const val DECIDE_EVERY_MS = 250L

const val SPEED_AHEAD = 0.96
const val SPEED_BEHIND = 1.04
const val SPEED_NORMAL = 1.0

private const val SEEK_SETTLE_MS = 1_500L
private const val PAUSED_TOLERANCE_MS = 60L
private const val FAR_MS = 1_000L
private const val CORRECTED_MS = 20L
private const val CORRECT_FROM_MS = 60L

/** Median of the last [RTT_SAMPLES] round trips (index n / 2 once sorted); 0 without any. */
fun rttMs(samples: List<Long>): Long {
    val last = samples.takeLast(RTT_SAMPLES).sorted()
    return if (last.isEmpty()) 0 else last[last.size / 2]
}

/** Where the TV should be at [now]: the leader's projected position, held back by [offsetMs], never below 0. */
fun targetMs(positionMs: Long, paused: Boolean, buffering: Boolean, recvAt: Long, now: Long, rttMs: Long, offsetMs: Long): Long {
    val moved = if (paused || buffering) 0 else (now - recvAt) + Math.floorDiv(rttMs, 2L)
    return (positionMs + moved - offsetMs).coerceAtLeast(0)
}

/** A stored « Décalage TV »: anything else reads as the nearest step below, within bounds. */
fun offeredOffset(ms: Int): Int = ms.coerceIn(0, MAX_OFFSET_MS) / OFFSET_STEP_MS * OFFSET_STEP_MS

/** The next « Décalage TV » on OK, back to 0 after the last. */
fun nextOffset(ms: Int): Int = offeredOffset(ms).let { if (it >= MAX_OFFSET_MS) 0 else it + OFFSET_STEP_MS }

/** What the player does now; nothing set means nothing to do. */
data class Correction(val pause: Boolean = false, val resume: Boolean = false, val speed: Double? = null, val seekTo: Long? = null)

/**
 * The ADR's decision, for the gap [e] = TV position − target (positive when the TV is
 * ahead). [correcting]: the speed is away from 1. [sinceSeekMs]: time since the last seek.
 */
fun decide(e: Long, targetMs: Long, leaderPaused: Boolean, tvPaused: Boolean, correcting: Boolean, sinceSeekMs: Long): Correction {
    if (sinceSeekMs < SEEK_SETTLE_MS) return Correction()
    val back = if (correcting) SPEED_NORMAL else null
    if (leaderPaused) {
        return Correction(pause = !tvPaused, speed = back, seekTo = targetMs.takeIf { abs(e) > PAUSED_TOLERANCE_MS })
    }
    val resume = tvPaused
    return when {
        abs(e) > FAR_MS -> Correction(resume = resume, speed = back, seekTo = targetMs)
        correcting && abs(e) <= CORRECTED_MS -> Correction(resume = resume, speed = SPEED_NORMAL)
        correcting || abs(e) >= CORRECT_FROM_MS -> Correction(resume = resume, speed = if (e > 0) SPEED_AHEAD else SPEED_BEHIND)
        else -> Correction(resume = resume)
    }
}
