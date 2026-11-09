package io.github.quentinpn.viewer.tv

import android.util.Log
import io.github.quentinpn.viewer.tv.emby.ActivePlayback
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.PlaybackTracker
import io.github.quentinpn.viewer.tv.emby.Report
import io.github.quentinpn.viewer.tv.emby.ReportKind
import io.github.quentinpn.viewer.tv.emby.ResendSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ViewerReport"
private const val STOP_RETRY_DELAY_MS = 1_000L

/**
 * Sends playback reports in order from one process-wide coroutine (ADR 0008, 0040),
 * so the stop report still goes out after the player activity is destroyed.
 */
class PlaybackReporter {
    private sealed interface Message {
        class Begin(val client: EmbyClient, val playback: ActivePlayback) : Message

        class Event(val apply: PlaybackTracker.(now: Long) -> List<Report>) : Message
    }

    /** A stop report the server did not get, and the session it belongs to (ADR 0024). */
    private class LostStop(val report: Report, val owner: EmbyClient, val schedule: ResendSchedule)

    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val tracker = PlaybackTracker()
    private var lost: LostStop? = null
    private val stops = MutableStateFlow(0)

    /** Counts stop reports handled: screens reload their user data when it changes. */
    val stopped: StateFlow<Int> = stops.asStateFlow()

    init {
        CoroutineScope(SupervisorJob()).launch { run() }
    }

    fun begin(client: EmbyClient, playback: ActivePlayback) {
        inbox.trySend(Message.Begin(client, playback))
    }

    /** Applies a player event to the tracker on the reporting coroutine. */
    fun event(apply: PlaybackTracker.(now: Long) -> List<Report>) {
        inbox.trySend(Message.Event(apply))
    }

    private suspend fun run() {
        var client: EmbyClient? = null
        while (true) {
            // Wakes up every second at most, so progress is also sent while paused
            val message = withTimeoutOrNull(1_000) { inbox.receive() }
            val now = now()
            // Reports go to the session of the playback they belong to
            val owner = client
            val reports = when (message) {
                is Message.Begin -> {
                    // A late stop must never overwrite the position of a newer playback
                    lost = null
                    tracker.begin(message.playback, now).also { client = message.client }
                }
                is Message.Event -> message.apply(tracker, now)
                null -> emptyList()
            }
            owner?.let { c -> reports.forEach { deliver(c, it) } }
            client?.let { c -> tracker.tick(now).forEach { deliver(c, it) } }
            if (lost?.schedule?.due(now()) == true) resend()
        }
    }

    private suspend fun deliver(client: EmbyClient, report: Report) {
        var error = send(client, report)
        if (report.kind == ReportKind.Stopped && error.isTransient()) {
            delay(STOP_RETRY_DELAY_MS)
            error = send(client, report)
        }
        if (error != null) Log.w(TAG, "report ${report.kind} not sent: ${error.message}")
        else Log.i(TAG, "report ${report.kind} ${report.body.eventName ?: ""} position=${report.body.positionTicks}")
        if (report.kind != ReportKind.Stopped) return
        // The stop of a playback replaced by a new one stays lost, same rule as Begin
        if (error.isTransient() && tracker.idle) lost = LostStop(report, client, ResendSchedule(now()))
        stops.value++
    }

    /** Tries a lost stop report again; it stays lost only if the failure may pass. */
    private suspend fun resend() {
        val stop = lost ?: return
        lost = null
        if (stop.schedule.expired(now())) {
            Log.w(TAG, "stop report given up")
            return
        }
        // The server ignores the position of a stop once it closed the playback itself;
        // a progress report carries it and reopens the playback
        val error = send(stop.owner, stop.report.lateProgress()) ?: send(stop.owner, stop.report)
        when {
            error == null -> {
                Log.i(TAG, "stop report sent after the cut position=${stop.report.body.positionTicks}")
                stops.value++
            }
            error.isTransient() -> {
                stop.schedule.postpone(now())
                lost = stop
            }
            else -> Log.w(TAG, "stop report given up: ${error.message}")
        }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private suspend fun send(client: EmbyClient, report: Report): EmbyError? = try {
        client.reportPlayback(report)
        null
    } catch (e: EmbyException) {
        e.error
    }

    private fun EmbyError?.isTransient() = this == EmbyError.Unreachable || this is EmbyError.Server
}
