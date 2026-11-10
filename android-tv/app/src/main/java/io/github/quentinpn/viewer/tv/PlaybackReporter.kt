package io.github.quentinpn.viewer.tv

import android.util.Log
import io.github.quentinpn.viewer.tv.emby.ActivePlayback
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.PendingStopStorage
import io.github.quentinpn.viewer.tv.emby.PlaybackTracker
import io.github.quentinpn.viewer.tv.emby.Report
import io.github.quentinpn.viewer.tv.emby.ReportKind
import io.github.quentinpn.viewer.tv.emby.StopResender
import io.github.quentinpn.viewer.tv.emby.accountKey
import io.github.quentinpn.viewer.tv.emby.isTransient
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
 * so the stop report still goes out after the player activity is destroyed. A stop
 * report lost to the network is kept on disk and sent again (ADR 0066).
 */
class PlaybackReporter(storage: PendingStopStorage) {
    private sealed interface Message {
        class Begin(val owner: Owner, val playback: ActivePlayback) : Message

        class Event(val apply: PlaybackTracker.(now: Long) -> List<Report>) : Message

        class SignedIn(val owner: Owner) : Message

        data object SignedOut : Message
    }

    /** The session reports go to, and the account its lost stops are kept for. */
    private class Owner(val client: EmbyClient, val key: String)

    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val tracker = PlaybackTracker()
    private val resender = StopResender(storage)
    private val stops = MutableStateFlow(0)

    /** Counts stop reports handled: screens reload their user data when it changes. */
    val stopped: StateFlow<Int> = stops.asStateFlow()

    init {
        CoroutineScope(SupervisorJob()).launch { run() }
    }

    fun begin(client: EmbyClient, userId: String, playback: ActivePlayback) {
        inbox.trySend(Message.Begin(owner(client, userId), playback))
    }

    /** Applies a player event to the tracker on the reporting coroutine. */
    fun event(apply: PlaybackTracker.(now: Long) -> List<Report>) {
        inbox.trySend(Message.Event(apply))
    }

    /** An account is signed in or restored at start: its kept stop reports are sent now. */
    fun signedIn(client: EmbyClient, userId: String) {
        inbox.trySend(Message.SignedIn(owner(client, userId)))
    }

    /** Signed out or expired: kept stop reports wait for the same account to sign in again. */
    fun signedOut() {
        inbox.trySend(Message.SignedOut)
    }

    private fun owner(client: EmbyClient, userId: String) = Owner(client, accountKey(client.server, userId))

    private suspend fun run() {
        var owner: Owner? = null
        while (true) {
            // Wakes up every second at most, so progress is also sent while paused
            val message = withTimeoutOrNull(1_000) { inbox.receive() }
            val now = now()
            // Reports go to the session of the playback they belong to
            val reportsOwner = owner
            val reports = when (message) {
                is Message.Begin -> {
                    // A late stop must never overwrite the position of a newer playback of the item
                    resender.supersede(message.owner.key, message.playback.itemId)
                    // Restored straight into the player: its session is the signed-in one
                    if (resender.account == null) resender.use(message.owner.key, message.owner.client)
                    tracker.begin(message.playback, now).also { owner = message.owner }
                }
                is Message.Event -> message.apply(tracker, now)
                is Message.SignedIn -> {
                    resender.use(message.owner.key, message.owner.client)
                    emptyList()
                }
                Message.SignedOut -> {
                    resender.signedOut()
                    emptyList()
                }
                null -> emptyList()
            }
            reportsOwner?.let { o -> reports.forEach { deliver(o, it) } }
            owner?.let { o -> tracker.tick(now).forEach { deliver(o, it) } }
            // Between playbacks only, as a stop that fails during one would wait for its end anyway
            if (tracker.idle && resender.due()) resend()
        }
    }

    private suspend fun deliver(owner: Owner, report: Report) {
        var error = send(owner.client, report)
        if (report.kind == ReportKind.Stopped && error.isTransient()) {
            delay(STOP_RETRY_DELAY_MS)
            error = send(owner.client, report)
        }
        if (error != null) Log.w(TAG, "report ${report.kind} not sent: ${error.message}")
        else Log.i(TAG, "report ${report.kind} ${report.body.eventName ?: ""} position=${report.body.positionTicks}")
        if (error == null) {
            // A kept stop of this item is now older than what the server has
            resender.supersede(owner.key, report.body.itemId)
            resender.reachable()
        }
        if (report.kind != ReportKind.Stopped) return
        // Unless the same item plays again: its new playback is newer
        if (error.isTransient() && tracker.itemId != report.body.itemId) {
            resender.keep(owner.key, report.body)
            Log.i(TAG, "stop report kept for later position=${report.body.positionTicks}")
        }
        stops.value++
    }

    /** Sends the kept stop reports again; screens reload once one goes through. */
    private suspend fun resend() {
        val outcome = resender.resend()
        if (outcome.sent > 0) {
            Log.i(TAG, "${outcome.sent} kept stop report(s) sent")
            stops.value++
        }
        if (outcome.dropped > 0) Log.w(TAG, "${outcome.dropped} kept stop report(s) refused, given up")
        outcome.error?.takeIf { it.isTransient() || it == EmbyError.SessionExpired }
            ?.let { Log.w(TAG, "kept stop reports not sent: ${it.message}") }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private suspend fun send(client: EmbyClient, report: Report): EmbyError? = try {
        client.reportPlayback(report)
        null
    } catch (e: EmbyException) {
        e.error
    }
}
