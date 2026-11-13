package io.github.quentinpn.viewer.tv.emby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// Server push of library changes over Emby's WebSocket: the home reads its shelves
// again as soon as a title is added, instead of waiting for the 5 min poll.

/** Message the client sends so the server keeps the session's socket. */
internal const val KEEP_ALIVE_MESSAGE = """{"MessageType":"KeepAlive"}"""

/** How often [KEEP_ALIVE_MESSAGE] is sent. */
internal const val KEEP_ALIVE_MS = 30_000L

/** Quiet time after the last library change before the shelves are read again. */
internal const val LIBRARY_DEBOUNCE_MS = 2_000L

private val RECONNECT_STEPS_MS = longArrayOf(2_000, 5_000, 15_000, 30_000)

/** Wait before reconnect attempt [attempt] (0-based): 2 s, 5 s, 15 s, then 30 s. */
internal fun reconnectDelay(attempt: Int): Long =
    RECONNECT_STEPS_MS[attempt.coerceIn(0, RECONNECT_STEPS_MS.lastIndex)]

private val eventsJson = Json { ignoreUnknownKeys = true }

/**
 * True when [text], a frame pushed by the server, is a `LibraryChanged` whose items were
 * added, updated or removed. Anything malformed or of another type is ignored.
 */
internal fun isLibraryChange(text: String): Boolean {
    val root = runCatching { eventsJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return false
    val type = (root["MessageType"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (type != "LibraryChanged") return false
    val data = root["Data"] as? JsonObject ?: return false
    return listOf("ItemsAdded", "ItemsUpdated", "ItemsRemoved").any { key ->
        (data[key] as? JsonArray)?.isNotEmpty() == true
    }
}

/**
 * Emits once per burst of library changes, [quietMs] after the last one (trailing debounce):
 * a scan that adds twenty titles reads the shelves once.
 */
internal fun Flow<Unit>.settled(quietMs: Long): Flow<Unit> {
    val upstream = this
    return channelFlow {
        val pulses = Channel<Unit>(Channel.CONFLATED)
        launch { upstream.collect { pulses.send(Unit) } }
        while (true) {
            pulses.receive()
            // Restart the wait at each new change
            while (withTimeoutOrNull(quietMs) { pulses.receive() } != null) Unit
            send(Unit)
        }
    }
}

/**
 * Library changes of the signed-in user, while collected: one Unit per qualifying frame.
 * The socket reconnects after [reconnectDelay] when it fails or closes, and is closed when
 * collection stops. Failures are never logged: the socket URL carries the token.
 */
fun EmbyClient.libraryChanges(): Flow<Unit> = channelFlow {
    var attempt = 0
    while (true) {
        val ended = Channel<Unit>(Channel.CONFLATED)
        val socket = openEventSocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isLibraryChange(text)) trySend(Unit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                ended.trySend(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.trySend(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended.trySend(Unit)
            }
        })
        try {
            coroutineScope {
                val keepAlive = launch {
                    while (true) {
                        delay(KEEP_ALIVE_MS)
                        socket.send(KEEP_ALIVE_MESSAGE)
                    }
                }
                ended.receive()
                keepAlive.cancel()
            }
        } catch (e: CancellationException) {
            socket.close(1000, null)
            throw e
        }
        socket.cancel()
        delay(reconnectDelay(attempt++))
    }
}
