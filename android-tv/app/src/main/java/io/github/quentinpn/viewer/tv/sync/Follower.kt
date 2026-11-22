package io.github.quentinpn.viewer.tv.sync

import kotlin.math.abs

// The TV's side of a link: pure, driven by the caller with its monotonic clock.

/** Without `load` or `bye` after `join`, the request is dropped. */
const val JOIN_TIMEOUT_MS = 10_000L

/** A `load` accepted leaves the TV this long to send `ready`. */
const val LOAD_TIMEOUT_MS = 30_000L

/** Without `state` or `pong` for this long, the link is lost. */
const val LOSS_MS = 5_000L

const val PING_EVERY_MS = 1_000L

/** Shown when the desktop answers `bye idle` to a `join`. */
const val IDLE_MESSAGE = "L'ordi ne lit rien pour l'instant."

/** The device list is empty. */
const val NO_DESKTOP_MESSAGE = "Aucun ordi Viewer n'est ouvert."

fun busyMessage(device: String) = "$device suit déjà une autre lecture."

fun noAnswerMessage(device: String) = "$device ne répond pas."

fun lostMessage(device: String) = "Lien perdu avec $device. La lecture continue ici."

/** The OSD pill: « Lié à Bureau · ±40 ms », the name alone until a gap is measured. */
fun linkPill(device: String, driftMs: Long?): String = if (driftMs == null) "Lié à $device" else "Lié à $device · ±${abs(driftMs)} ms"

sealed interface Phase {
    val peer: String?

    data object Idle : Phase {
        override val peer: String? = null
    }

    /** `join` sent, waiting for `load` or `bye`. */
    data class Joining(override val peer: String, val since: Long) : Phase

    /** A `load` accepted: the title is loading, paused, until `ready`. */
    data class Loading(override val peer: String, val since: Long, val load: Body.Load) : Phase

    data class Linked(override val peer: String) : Phase
}

/** The leader's last `state`, its position taken from the least delayed recent one, and when it arrived. */
data class LeaderState(val state: Body.State, val recvAt: Long)

sealed interface Effect {
    /** Relay [envelope] to the session [to]. */
    data class Send(val to: String, val envelope: Envelope) : Effect

    /** Open this title paused at its position, then call [Follower.ready]. */
    data class Load(val load: Body.Load) : Effect

    /** The leader stopped: the player closes as if the user had left it. */
    data object Close : Effect

    data class Notice(val text: String) : Effect

    /** A link ended, whatever the reason: the player plays on alone, at normal speed. Always last. */
    data object Unlinked : Effect
}

/**
 * The follower state machine. [self] is the TV's own session id, known once the socket
 * is open; nothing is sent or accepted without it. [nameOf] gives a session's
 * `DeviceName` from the server's list, never from a message. [newLink] draws a link id.
 */
class Follower(private val newLink: () -> String, private val nameOf: (String) -> String) {
    var self: String? = null

    var phase: Phase = Phase.Idle
        private set

    var leader: LeaderState? = null
        private set

    /** Last gap measured by the player, sent with each `ping`. */
    var driftMs: Long? = null

    private var cursor: LinkCursor? = null
    private var seq = 0L
    private var lastHeard = 0L
    private var lastPing: Long? = null
    private val rtts = ArrayDeque<Long>()
    private var anchors = emptyList<Long>()

    val rttMs: Long get() = rttMs(rtts.toList())

    val linked: Boolean get() = phase is Phase.Linked

    /** « Regarder avec l'ordi » on [peer]. Only out of a link. */
    fun join(peer: String, now: Long): List<Effect> {
        val from = self ?: return emptyList()
        if (phase != Phase.Idle || peer == from) return emptyList()
        val link = newLink()
        open(LinkCursor(peer, link, null), now)
        phase = Phase.Joining(peer, now)
        return listOf(send(Body.Join))
    }

    fun receive(envelope: Envelope, now: Long): List<Effect> {
        if (self == null) return emptyList()
        val body = envelope.body
        when (accept(cursor, envelope.from, envelope.link, envelope.seq, body.kind)) {
            Acceptance.Ignore -> return emptyList()
            Acceptance.Busy -> return listOf(Effect.Send(envelope.from, envelope(envelope.link, Body.Bye(ByeReason.Busy))))
            Acceptance.Accept -> Unit
        }
        if (cursor == null) {
            // Out of a link, only a desktop's `load` opens one
            if (body !is Body.Load) return emptyList()
            open(LinkCursor(envelope.from, envelope.link, null), now)
        }
        cursor = cursor?.copy(lastSeq = envelope.seq)
        val peer = envelope.from
        return when (body) {
            is Body.Load -> {
                phase = Phase.Loading(peer, now, body)
                leader = null
                anchors = emptyList()
                lastHeard = now
                listOf(Effect.Load(body))
            }
            is Body.State -> {
                if (phase is Phase.Joining) return emptyList()
                // Paused or buffering, the position does not move: nothing to project from
                val kept = if (leader?.state?.itemId == body.itemId) anchors else emptyList()
                anchors = if (body.paused || body.buffering) emptyList() else keepAnchor(kept, body.positionMs - now)
                val position = anchors.maxOrNull()?.let { it + now } ?: body.positionMs
                leader = LeaderState(body.copy(positionMs = position), now)
                lastHeard = now
                emptyList()
            }
            is Body.Pong -> {
                if (phase is Phase.Joining) return emptyList()
                if (body.t <= now) {
                    rtts.addLast(now - body.t)
                    while (rtts.size > RTT_SAMPLES) rtts.removeFirst()
                }
                lastHeard = now
                emptyList()
            }
            is Body.Bye -> bye(peer, body.reason)
            else -> emptyList()
        }
    }

    /** The clock moved on: pings, waits and the loss of the link. */
    fun tick(now: Long): List<Effect> {
        val current = phase
        val peer = current.peer ?: return emptyList()
        val name = nameOf(peer)
        return when {
            current is Phase.Joining && now - current.since >= JOIN_TIMEOUT_MS -> end(ByeReason.Timeout, noAnswerMessage(name))
            current is Phase.Loading && now - current.since >= LOAD_TIMEOUT_MS -> end(ByeReason.Timeout, lostMessage(name))
            current is Phase.Joining -> emptyList()
            now - lastHeard >= LOSS_MS -> listOf(Effect.Notice(lostMessage(name)), close())
            lastPing.let { it == null || now - it >= PING_EVERY_MS } -> {
                lastPing = now
                listOf(send(Body.Ping(now, driftMs.takeIf { linked })))
            }
            else -> emptyList()
        }
    }

    /** The title of the last `load` is buffered and paused at [positionMs]. */
    fun ready(itemId: String, positionMs: Long, now: Long): List<Effect> {
        val loading = phase as? Phase.Loading ?: return emptyList()
        if (loading.load.itemId != itemId) return emptyList()
        phase = Phase.Linked(loading.peer)
        lastHeard = now
        return listOf(send(Body.Ready(itemId, positionMs.coerceIn(0, MAX_POSITION_MS))))
    }

    /** An action of the user on the TV, already applied there; seeks are absolute. */
    fun command(action: CmdAction, positionMs: Long? = null): List<Effect> {
        if (!linked) return emptyList()
        val at = if (action == CmdAction.Seek) (positionMs ?: return emptyList()).coerceIn(0, MAX_POSITION_MS) else null
        return listOf(send(Body.Cmd(action, at)))
    }

    fun buffering(on: Boolean): List<Effect> = if (linked) listOf(send(Body.Buffering(on))) else emptyList()

    /** « Délier »: the desktop goes on alone, so does the TV. */
    fun unlink(): List<Effect> = end(ByeReason.Left, null)

    /** Back or the player closed: the desktop stops too. */
    fun stopped(): List<Effect> = end(ByeReason.Stopped, null)

    /** Where the TV should be at [now], once linked and a `state` came. */
    fun target(now: Long, offsetMs: Long): Long? {
        if (!linked) return null
        val last = leader ?: return null
        val s = last.state
        return targetMs(s.positionMs, s.paused, s.buffering, last.recvAt, now, rttMs, offsetMs)
    }

    private fun bye(peer: String, reason: ByeReason): List<Effect> {
        val name = nameOf(peer)
        val joining = phase is Phase.Joining
        val ended = close()
        val effect = when {
            joining && reason == ByeReason.Idle -> Effect.Notice(IDLE_MESSAGE)
            joining && reason == ByeReason.Busy -> Effect.Notice(busyMessage(name))
            joining -> Effect.Notice(noAnswerMessage(name))
            reason == ByeReason.Stopped -> Effect.Close
            // « Délier » on the desktop: nothing to say, the pill goes
            reason == ByeReason.Left -> null
            else -> Effect.Notice(lostMessage(name))
        }
        return listOfNotNull(effect, ended)
    }

    private fun end(reason: ByeReason, notice: String?): List<Effect> {
        if (phase == Phase.Idle) return emptyList()
        val bye = send(Body.Bye(reason))
        val ended = close()
        return listOfNotNull(bye, notice?.let(Effect::Notice), ended)
    }

    private fun open(link: LinkCursor, now: Long) {
        cursor = link
        lastHeard = now
        lastPing = null
        rtts.clear()
        driftMs = null
        leader = null
        anchors = emptyList()
    }

    private fun close(): Effect {
        phase = Phase.Idle
        cursor = null
        leader = null
        anchors = emptyList()
        driftMs = null
        return Effect.Unlinked
    }

    private fun send(body: Body): Effect.Send {
        val link = checkNotNull(cursor)
        return Effect.Send(link.peer, envelope(link.link, body))
    }

    private fun envelope(link: String, body: Body) = Envelope(checkNotNull(self), link, seq++, body)
}
