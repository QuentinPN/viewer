package io.github.quentinpn.viewer.tv.sync

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.quentinpn.viewer.tv.PlayerActivity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ViewerSync"

/** The player while it is open: the hub drives it from the link. */
interface LinkedPlayer {
    /** A `load` from the leader: open this title paused, then call [SyncHub.ready]. */
    fun linkedLoad(load: Body.Load)

    /** The leader stopped. */
    fun linkedClose()

    /** The link ended: speed back to 1, the player's own behaviour again. */
    fun linkedEnded()

    /** Every [DECIDE_EVERY_MS] and at each `state`: recalage toward the leader. */
    fun evaluate()
}

/** What the screens show of the link. */
data class LinkView(val phase: Phase = Phase.Idle, val device: String? = null, val driftMs: Long? = null) {
    val linked: Boolean get() = phase is Phase.Linked
    val busy: Boolean get() = phase != Phase.Idle
}

/**
 * The session-wide Emby socket and the follower. Held by each started
 * activity, so it stays open from the home to the player; closed when nobody holds it,
 * which also happens at sign-out. Runs on the main thread. Logs keep only a kind, a seq
 * or a reject reason: never an envelope, never the token.
 */
class SyncHub(private val app: Context) {
    private val scope: CoroutineScope = MainScope()
    private val follower = Follower(newLink = { UUID.randomUUID().toString() }, nameOf = ::nameOf)

    private val _frames = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** Every frame the server pushes; the home picks `LibraryChanged` among them. */
    val frames: SharedFlow<String> = _frames

    private val _view = MutableStateFlow(LinkView())
    val view: StateFlow<LinkView> = _view

    private val _notice = MutableStateFlow<String?>(null)

    /** The last message for the user, until shown. */
    val notice: StateFlow<String?> = _notice

    private var holders = 0
    private var held: Pair<EmbyClient, String>? = null
    private var socket: Job? = null
    private var names: Map<String, String> = emptyMap()
    private var outbox = Channel<Effect.Send>(Channel.UNLIMITED)
    private var player: LinkedPlayer? = null
    private var pendingLoad: Body.Load? = null

    /** Started for a `load`, not attached yet: a later `load` only replaces [pendingLoad]. */
    private var opening = false

    private fun now() = SystemClock.elapsedRealtime()

    private fun nameOf(session: String) = names[session] ?: DEFAULT_DEVICE_NAME

    /** Keeps the socket open while the caller's coroutine runs (an activity started). */
    suspend fun hold(client: EmbyClient, userId: String) {
        holders++
        // Each screen builds its own client: only another session restarts the socket
        val same = held?.let { (c, u) -> c.sameSession(client) && u == userId } == true
        if (!same || socket == null) start(client, userId)
        try {
            awaitCancellation()
        } finally {
            holders--
            // Let the next activity take it before closing (A.onStop comes after B.onStart)
            scope.launch {
                if (holders == 0) stop()
            }
        }
    }

    private fun start(client: EmbyClient, userId: String) {
        stop()
        held = client to userId
        val sends = Channel<Effect.Send>(Channel.UNLIMITED).also { outbox = it }
        socket = scope.launch {
            launch {
                for (send in sends) {
                    Log.d(TAG, "send ${send.envelope.body.label} #${send.envelope.seq}")
                    try {
                        client.sendString(send.to, encodeEnvelope(send.envelope))
                    } catch (_: EmbyException) {
                        Log.w(TAG, "send failed: ${send.envelope.body.label} #${send.envelope.seq}")
                    }
                }
            }
            launch {
                while (true) {
                    delay(DECIDE_EVERY_MS)
                    run(follower.tick(now()))
                    if (follower.linked) player?.evaluate()
                }
            }
            // Inside the socket's job: a late answer of a closed socket never sets self or names
            val socketScope = this
            client.events(onOpen = { socketScope.launch { opened(client, userId) } }).collect(::frame)
        }
    }

    private fun stop() {
        val client = held?.first
        val ended = follower.unlink()
        socket?.cancel()
        socket = null
        outbox.close()
        held = null
        follower.self = null
        // The `bye left` goes out best effort, outside the closed socket's job
        val byes = ended.filterIsInstance<Effect.Send>()
        if (client != null && byes.isNotEmpty()) {
            scope.launch {
                for (bye in byes) {
                    Log.d(TAG, "send ${bye.envelope.body.label} #${bye.envelope.seq} (closing)")
                    try {
                        client.sendString(bye.to, encodeEnvelope(bye.envelope))
                    } catch (_: EmbyException) {
                        Log.w(TAG, "send failed: ${bye.envelope.body.label} #${bye.envelope.seq}")
                    }
                }
            }
        }
        if (Effect.Unlinked in ended) player?.linkedEnded()
        pendingLoad = null
        publish()
    }

    /** At each (re)connection: declare the session, then find ourselves in the list. */
    private suspend fun opened(client: EmbyClient, userId: String) {
        try {
            client.postCapabilities(CAPABILITIES_BODY)
            refresh(client, userId)
        } catch (_: EmbyException) {
            Log.w(TAG, "capabilities failed")
        }
    }

    private suspend fun refresh(client: EmbyClient, userId: String): List<Device> {
        val sessions = client.sessions(userId)
        names = sessions.associate { it.id to displayName(it) }
        ownSessionId(sessions, client.deviceId)?.let { follower.self = it }
        return desktops(sessions, client.deviceId)
    }

    /** The desktops to offer, read again from the server; empty when it cannot be read. */
    suspend fun devices(): List<Device> {
        val (client, userId) = held ?: return emptyList()
        return try {
            refresh(client, userId)
        } catch (_: EmbyException) {
            emptyList()
        }
    }

    private fun frame(text: String) {
        _frames.tryEmit(text)
        val raw = sendStringOf(text) ?: return
        when (val parsed = parseEnvelope(raw)) {
            is Parsed.Rejected -> Log.w(TAG, "rejected: ${parsed.reason.code}")
            is Parsed.Ok -> {
                val body = parsed.envelope.body
                Log.d(TAG, "received ${body.label} #${parsed.envelope.seq}")
                run(follower.receive(parsed.envelope, now()))
                if (body is Body.State && follower.linked) player?.evaluate()
            }
        }
    }

    fun join(device: Device) = run(follower.join(device.sessionId, now()))

    fun ready(itemId: String, positionMs: Long) = run(follower.ready(itemId, positionMs, now()))

    fun command(action: CmdAction, positionMs: Long? = null) = run(follower.command(action, positionMs))

    fun buffering(on: Boolean) = run(follower.buffering(on))

    fun unlink() = run(follower.unlink())

    fun stopped() = run(follower.stopped())

    /** Where the player should be now, and the gap it measured, for the pill and the pings. */
    fun target(offsetMs: Long): Long? = follower.target(now(), offsetMs)

    fun leader(): Body.State? = follower.leader?.state

    /** The `load` of [itemId] still waits for its `ready`. */
    fun loading(itemId: String): Boolean = (follower.phase as? Phase.Loading)?.load?.itemId == itemId

    fun measured(driftMs: Long) {
        follower.driftMs = driftMs
        publish()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun attach(linked: LinkedPlayer) {
        player = linked
        opening = false
    }

    fun detach(linked: LinkedPlayer) {
        if (player === linked) player = null
    }

    /** The `load` that opened the player from another screen, once. */
    fun takePendingLoad(): Body.Load? = pendingLoad.also { pendingLoad = null }

    private fun run(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                is Effect.Send -> outbox.trySend(effect)
                is Effect.Load -> player?.linkedLoad(effect.load) ?: openPlayer(effect.load)
                Effect.Close -> player?.linkedClose()
                Effect.Unlinked -> player?.linkedEnded()
                is Effect.Notice -> _notice.value = effect.text
            }
        }
        publish()
    }

    private fun openPlayer(load: Body.Load) {
        pendingLoad = load
        if (opening) return
        opening = true
        app.startActivity(
            Intent(app, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PlayerActivity.EXTRA_ITEM_ID, load.itemId)
                .putExtra(PlayerActivity.EXTRA_LINKED, true),
        )
    }

    private fun publish() {
        val phase = follower.phase
        _view.value = LinkView(phase, phase.peer?.let(::nameOf), follower.driftMs)
    }
}
