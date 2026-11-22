package io.github.quentinpn.viewer.tv.emby

import kotlin.math.roundToLong

// Playback reporting, ported from the Windows client (src-tauri/src/emby/reporting.rs):
// player events become Emby start, progress and stop reports.

/** Progress cadence, also while paused so the server keeps the session alive. */
const val PROGRESS_INTERVAL_MS = 10_000L
private const val TICKS_PER_SECOND = 10_000_000.0

/** Margin before the end under which an end of file is a normal end (credits, rounding). */
private const val EARLY_EOF_TICKS = 30 * 10_000_000L

/** Progress event of a stream change, from the Emby wiki « Playback Check-ins ». */
const val QUALITY_CHANGE = "QualityChange"

enum class ReportKind(val path: String) {
    Start("/Sessions/Playing"),
    Progress("/Sessions/Playing/Progress"),
    Stopped("/Sessions/Playing/Stopped"),
}

data class Report(val kind: ReportKind, val body: PlaybackReport) {
    /** The progress report sent ahead of a lost stop report: it carries the position. */
    fun lateProgress() = Report(
        ReportKind.Progress,
        body.copy(isPaused = false, canSeek = true, eventName = "TimeUpdate", failed = null),
    )
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

    /** The stream that replaces the playing one, until its file loads. */
    private var switching: ActivePlayback? = null

    /** Nothing announced or playing: a lost stop report can still be resent. */
    val idle get() = pending == null && playing == null

    /** Item announced or playing: a lost stop of it is older than this playback. */
    val itemId get() = pending?.itemId ?: playing?.playback?.itemId

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
        switching = null
        pending = playback
        return out
    }

    /**
     * The same item goes on in another stream (quality changed in the player):
     * nothing is sent now, and no stop ever. Once the new file loads, a progress with
     * `EventName` `QualityChange` carries its source, session and method.
     */
    fun switched(playback: ActivePlayback) {
        if (playing?.playback?.itemId == playback.itemId) switching = playback
    }

    fun fileLoaded(now: Long): List<Report> {
        val swap = switching
        val current = playing
        if (swap != null && current != null) {
            switching = null
            // A duration found by mpv stays known if the server gave none
            current.playback = swap.copy(runTimeTicks = swap.runTimeTicks ?: current.playback.runTimeTicks)
            current.positionTicks = swap.startTicks
            current.seeked = false
            return listOf(current.report(ReportKind.Progress, QUALITY_CHANGE, now))
        }
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
        switching = null
        val p = playing ?: return emptyList()
        playing = null
        val report = p.report(ReportKind.Stopped, null, now)
        return listOf(report.copy(body = report.body.copy(failed = failed)))
    }
}

private fun ticks(seconds: Double): Long? =
    if (seconds.isFinite() && seconds >= 0) (seconds * TICKS_PER_SECOND).roundToLong() else null
