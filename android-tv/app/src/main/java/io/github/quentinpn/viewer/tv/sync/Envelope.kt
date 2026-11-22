package io.github.quentinpn.viewer.tv.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Messages of the linked playback, carried by Emby's SendString relay.
// Same contract as the desktop; both test suites read docs/sync/vectors.json.

/** Longest envelope accepted or sent, in UTF-8 bytes. */
const val MAX_ENVELOPE_BYTES = 1024

/** Positions are whole milliseconds, up to 24 h. */
const val MAX_POSITION_MS = 86_400_000L

private const val MAX_SEQ = Int.MAX_VALUE.toLong()

private val SESSION_ID = Regex("[A-Za-z0-9_-]{1,64}")
private val LINK_ID = Regex("[A-Za-z0-9_-]{8,64}")
private val INTEGER = Regex("-?[0-9]{1,18}")

enum class Kind(val wire: String) { Join("join"), Load("load"), Ready("ready"), State("state"), Cmd("cmd"), Buffering("buffering"), Ping("ping"), Pong("pong"), Bye("bye") }

enum class CmdAction(val wire: String) { Pause("pause"), Play("play"), Seek("seek"), Next("next"), Previous("previous") }

enum class ByeReason(val wire: String) { Stopped("stopped"), Left("left"), Idle("idle"), Busy("busy"), Timeout("timeout") }

/** The fields of each kind; positions in ms, track indexes as Emby's (−1 for none). */
sealed interface Body {
    val kind: Kind

    data object Join : Body {
        override val kind = Kind.Join
    }

    data class Load(
        val itemId: String,
        val mediaSourceId: String? = null,
        val positionMs: Long,
        val audioStreamIndex: Int? = null,
        val subtitleStreamIndex: Int? = null,
    ) : Body {
        override val kind = Kind.Load
    }

    data class Ready(val itemId: String, val positionMs: Long) : Body {
        override val kind = Kind.Ready
    }

    data class State(val itemId: String, val positionMs: Long, val paused: Boolean, val buffering: Boolean) : Body {
        override val kind = Kind.State
    }

    data class Cmd(val action: CmdAction, val positionMs: Long? = null) : Body {
        override val kind = Kind.Cmd
    }

    data class Buffering(val on: Boolean) : Body {
        override val kind = Kind.Buffering
    }

    data class Ping(val t: Long, val driftMs: Long? = null) : Body {
        override val kind = Kind.Ping
    }

    data class Pong(val t: Long) : Body {
        override val kind = Kind.Pong
    }

    data class Bye(val reason: ByeReason) : Body {
        override val kind = Kind.Bye
    }
}

data class Envelope(val from: String, val link: String, val seq: Long, val body: Body)

/** The kind, with the reason of a `bye`: what the logs keep of a message. */
val Body.label: String get() = if (this is Body.Bye) "bye ${reason.wire}" else kind.wire

/** Why an envelope was dropped; the only thing ever logged about it. */
enum class Reject(val code: String) { Json("json"), Size("size"), Version("version"), Field("field"), Kind("kind") }

sealed interface Parsed {
    data class Ok(val envelope: Envelope) : Parsed

    data class Rejected(val reason: Reject) : Parsed
}

private val envelopeJson = Json

private class Invalid(val reason: Reject) : Exception()

private fun field(): Nothing = throw Invalid(Reject.Field)

private fun JsonObject.present(name: String): JsonElement? = this[name]?.takeUnless { it is JsonNull }

private fun JsonObject.string(name: String, shape: Regex): String {
    val value = present(name) as? JsonPrimitive ?: field()
    if (!value.isString || !shape.matches(value.content)) field()
    return value.content
}

private fun JsonObject.integerOrNull(name: String, range: LongRange): Long? {
    val value = present(name) ?: return null
    val primitive = value as? JsonPrimitive ?: field()
    if (primitive.isString || !INTEGER.matches(primitive.content)) field()
    val number = primitive.content.toLong()
    if (number !in range) field()
    return number
}

private fun JsonObject.integer(name: String, range: LongRange): Long = integerOrNull(name, range) ?: field()

private fun JsonObject.boolean(name: String): Boolean {
    val value = present(name) as? JsonPrimitive ?: field()
    if (value.isString) field()
    return when (value.content) {
        "true" -> true
        "false" -> false
        else -> field()
    }
}

private fun <E> JsonObject.choice(name: String, entries: List<E>, wire: (E) -> String): E {
    val value = present(name) as? JsonPrimitive ?: field()
    if (!value.isString) field()
    return entries.find { wire(it) == value.content } ?: field()
}

private val TRACK_RANGE = -1L..999L
private val POSITION_RANGE = 0L..MAX_POSITION_MS

// The monotonic clock of the TV and the drift both fit well inside JSON's safe integers
private val CLOCK_RANGE = 0L..(1L shl 53)
private val DRIFT_RANGE = -MAX_POSITION_MS..MAX_POSITION_MS

private fun JsonObject.body(kind: Kind): Body = when (kind) {
    Kind.Join -> Body.Join
    Kind.Load -> Body.Load(
        itemId = string("itemId", SESSION_ID),
        mediaSourceId = present("mediaSourceId")?.let { string("mediaSourceId", SESSION_ID) },
        positionMs = integer("positionMs", POSITION_RANGE),
        audioStreamIndex = integerOrNull("audioStreamIndex", TRACK_RANGE)?.toInt(),
        subtitleStreamIndex = integerOrNull("subtitleStreamIndex", TRACK_RANGE)?.toInt(),
    )
    Kind.Ready -> Body.Ready(string("itemId", SESSION_ID), integer("positionMs", POSITION_RANGE))
    Kind.State -> Body.State(string("itemId", SESSION_ID), integer("positionMs", POSITION_RANGE), boolean("paused"), boolean("buffering"))
    Kind.Cmd -> {
        val action = choice("action", CmdAction.entries) { it.wire }
        Body.Cmd(action, if (action == CmdAction.Seek) integer("positionMs", POSITION_RANGE) else null)
    }
    Kind.Buffering -> Body.Buffering(boolean("on"))
    Kind.Ping -> Body.Ping(integer("t", CLOCK_RANGE), integerOrNull("driftMs", DRIFT_RANGE))
    Kind.Pong -> Body.Pong(integer("t", CLOCK_RANGE))
    Kind.Bye -> Body.Bye(choice("reason", ByeReason.entries) { it.wire })
}

/** Reads one envelope; anything invalid is rejected with its reason. Unknown fields are ignored. */
fun parseEnvelope(raw: String): Parsed = try {
    if (raw.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) throw Invalid(Reject.Size)
    val root = runCatching { envelopeJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: throw Invalid(Reject.Json)
    val viewer = root["tv"] as? JsonPrimitive
    val v = root["v"] as? JsonPrimitive
    if (viewer?.isString != true || viewer.content != "sync" || v == null || v.isString || v.content != "1") throw Invalid(Reject.Version)
    val from = root.string("from", SESSION_ID)
    val link = root.string("link", LINK_ID)
    val seq = root.integer("seq", 0L..MAX_SEQ)
    val kindName = root.present("kind") as? JsonPrimitive ?: field()
    if (!kindName.isString) field()
    val kind = Kind.entries.find { it.wire == kindName.content } ?: throw Invalid(Reject.Kind)
    Parsed.Ok(Envelope(from, link, seq, root.body(kind)))
} catch (e: Invalid) {
    Parsed.Rejected(e.reason)
}

/** One line of JSON, optional fields left out when absent. */
fun encodeEnvelope(envelope: Envelope): String = buildJsonObject {
    put("tv", "sync")
    put("v", 1)
    put("from", envelope.from)
    put("link", envelope.link)
    put("seq", envelope.seq)
    put("kind", envelope.body.kind.wire)
    when (val body = envelope.body) {
        Body.Join -> Unit
        is Body.Load -> {
            put("itemId", body.itemId)
            body.mediaSourceId?.let { put("mediaSourceId", it) }
            put("positionMs", body.positionMs)
            body.audioStreamIndex?.let { put("audioStreamIndex", it) }
            body.subtitleStreamIndex?.let { put("subtitleStreamIndex", it) }
        }
        is Body.Ready -> {
            put("itemId", body.itemId)
            put("positionMs", body.positionMs)
        }
        is Body.State -> {
            put("itemId", body.itemId)
            put("positionMs", body.positionMs)
            put("paused", body.paused)
            put("buffering", body.buffering)
        }
        is Body.Cmd -> {
            put("action", body.action.wire)
            body.positionMs?.let { put("positionMs", it) }
        }
        is Body.Buffering -> put("on", body.on)
        is Body.Ping -> {
            put("t", body.t)
            body.driftMs?.let { put("driftMs", it) }
        }
        is Body.Pong -> put("t", body.t)
        is Body.Bye -> put("reason", body.reason.wire)
    }
}.toString()

/** Where the receiver stands in a link: its peer, the link and the last seq read from that peer. */
data class LinkCursor(val peer: String, val link: String, val lastSeq: Long?)

enum class Acceptance { Accept, Ignore, Busy }

/**
 * The filter. Out of a link, only `join` and `load` are read: they open one. In a
 * link, only the peer's messages of that link, with a seq above the last one; a `join`
 * or `load` of another session gets `bye busy`.
 */
fun accept(cursor: LinkCursor?, from: String, link: String, seq: Long, kind: Kind): Acceptance {
    val opens = kind == Kind.Join || kind == Kind.Load
    if (cursor == null) return if (opens) Acceptance.Accept else Acceptance.Ignore
    if (from == cursor.peer) {
        val fresh = cursor.lastSeq == null || seq > cursor.lastSeq
        return if (link == cursor.link && fresh) Acceptance.Accept else Acceptance.Ignore
    }
    return if (opens) Acceptance.Busy else Acceptance.Ignore
}
