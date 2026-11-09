package io.github.quentinpn.viewer.tv.emby

import kotlin.math.roundToLong

// Playback reporting, ported from the Windows client (src-tauri/src/emby/reporting.rs,
// ADR 0008): player events become Emby start, progress and stop reports.

/** Progress cadence, also while paused so the server keeps the session alive. */
const val PROGRESS_INTERVAL_MS = 10_000L
private const val TICKS_PER_SECOND = 10_000_000.0

/** Margin before the end under which an end of file is a normal end (credits, rounding). */
private const val EARLY_EOF_TICKS = 30 * 10_000_000L

enum class ReportKind(val path: String) {
    Start("/Sessions/Playing"),
    Progress("/Sessions/Playing/Progress"),
    Stopped("/Sessions/Playing/Stopped"),
}

data class Report(val kind: ReportKind, val body: PlaybackReport) {
    /** The progress report sent ahead of a lost stop report: it carries the position (ADR 0024). */
    fun lateProgress() = Report(
        ReportKind.Progress,
        body.copy(isPaused = false, canSeek = true, eventName = "TimeUpdate", failed = null),
    )
}

/** A lost stop report (ADR 0024): waits between tries, doubled up to the cap. */
const val STOP_RESEND_FIRST_MS = 5_000L
const val STOP_RESEND_CAP_MS = 15_000L

/** An older report could overwrite a playback made elsewhere in the meantime. */
const val STOP_RESEND_GIVE_UP_MS = 3_600_000L

/** When a lost stop report is sent again, and when it is given up; times in ms. */
class ResendSchedule(now: Long) {
    private val giveUpAt = now + STOP_RESEND_GIVE_UP_MS
    private var delay = STOP_RESEND_FIRST_MS
    private var nextAt = now + delay

    fun due(now: Long) = now >= nextAt

    fun expired(now: Long) = now >= giveUpAt

    /** The try at [now] failed. */
    fun postpone(now: Long) {
        delay = (delay * 2).coerceAtMost(STOP_RESEND_CAP_MS)
        nextAt = now + delay
    }
}

/** A playback announced before its file is loaded. */
data class ActivePlayback(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String?,
    val method: PlayMethod,
    val runTimeTicks: Long?,
    val startTicks: Long,
)

/** Pure state machine from player events to reports; time (ms) is passed in. */
class PlaybackTracker {
    private var pending: ActivePlayback? = null
    private var playing: Playing? = null

    /** Nothing announced or playing: a lost stop report can still be resent. */
    val idle get() = pending == null && playing == null

    private class Playing(
        var playback: ActivePlayback,
        var positionTicks: Long,
        var paused: Boolean = false,
        var lastSent: Long,
        /** A seek started: report the next position. */
        var seeked: Boolean = false,
    ) {
        /** mpv reports a stream cut by the network as an end of file once its cache drains. */
        fun endedEarly() = playback.runTimeTicks?.let { it - positionTicks > EARLY_EOF_TICKS } ?: false

        fun report(kind: ReportKind, event: String?, now: Long): Report {
            lastSent = now
            val live = kind != ReportKind.Stopped
            val p = playback
            return Report(
                kind,
                PlaybackReport(
                    itemId = p.itemId,
                    mediaSourceId = p.mediaSourceId,
                    playSessionId = p.playSessionId,
                    positionTicks = positionTicks,
                    runTimeTicks = p.runTimeTicks,
                    isPaused = if (live) paused else null,
                    canSeek = if (live) true else null,
                    playMethod = p.method.name,
                    eventName = event,
                ),
            )
        }
    }

    /** Announces a new playback; one still playing is reported stopped first. */
    fun begin(playback: ActivePlayback, now: Long): List<Report> {
        val out = stop(failed = false, now)
        pending = playback
        return out
    }

    fun fileLoaded(now: Long): List<Report> {
        val playback = pending ?: return emptyList()
        pending = null
        val p = Playing(playback, positionTicks = playback.startTicks, lastSent = now)
        playing = p
        return listOf(p.report(ReportKind.Start, null, now))
    }

    fun position(seconds: Double, now: Long): List<Report> {
        val p = playing ?: return emptyList()
        ticks(seconds)?.let { p.positionTicks = it }
        if (!p.seeked) return emptyList()
        p.seeked = false
        return listOf(p.report(ReportKind.Progress, "TimeUpdate", now))
    }

    fun duration(seconds: Double) {
        val p = playing ?: return
        if (p.playback.runTimeTicks == null) {
            ticks(seconds)?.takeIf { it > 0 }?.let { p.playback = p.playback.copy(runTimeTicks = it) }
        }
    }

    fun paused(paused: Boolean, now: Long): List<Report> {
        val p = playing ?: return emptyList()
        // mpv reports the initial unpaused state: no change, nothing sent
        if (paused == p.paused) return emptyList()
        p.paused = paused
        return listOf(p.report(ReportKind.Progress, if (paused) "Pause" else "Unpause", now))
    }

    fun seeked() {
        playing?.seeked = true
    }

    /**
     * The file ended on its own (end reached, error or cut stream). Well before the
     * known duration, it is reported failed so the server never marks it watched.
     */
    fun ended(now: Long): List<Report> {
        val failed = playing?.endedEarly() ?: false
        pending = null
        return stop(failed, now)
    }

    /** The user left the player. */
    fun closed(now: Long): List<Report> {
        pending = null
        return stop(failed = false, now)
    }

    /** Periodic progress, due every [PROGRESS_INTERVAL_MS] since the last report. */
    fun tick(now: Long): List<Report> {
        val p = playing ?: return emptyList()
        if (now - p.lastSent < PROGRESS_INTERVAL_MS) return emptyList()
        return listOf(p.report(ReportKind.Progress, "TimeUpdate", now))
    }

    private fun stop(failed: Boolean, now: Long): List<Report> {
        val p = playing ?: return emptyList()
        playing = null
        val report = p.report(ReportKind.Stopped, null, now)
        return listOf(report.copy(body = report.body.copy(failed = failed)))
    }
}

private fun ticks(seconds: Double): Long? =
    if (seconds.isFinite() && seconds >= 0) (seconds * TICKS_PER_SECOND).roundToLong() else null
