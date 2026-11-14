package io.github.quentinpn.viewer.tv.sync

import io.github.quentinpn.viewer.tv.emby.SessionInfoDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The Emby side of the relay (ADR 0123): who is online, and the strings pushed on the socket.

/** `Client` of the desktop app's sessions, as seen from the TV. */
const val DESKTOP_CLIENT = "Viewer"

/** Declared at each socket opening: without `SupportsMediaControl`, neither app is listed. */
const val CAPABILITIES_BODY = """{"PlayableMediaTypes":["Video"],"SupportedCommands":["SendString"],"SupportsMediaControl":true}"""

/** A session's name when the server gives none. */
const val DEFAULT_DEVICE_NAME = "L'ordi"

/** The name the server gives a session, else [DEFAULT_DEVICE_NAME]. */
fun displayName(session: SessionInfoDto): String = session.deviceName?.takeIf(String::isNotBlank) ?: DEFAULT_DEVICE_NAME

/** An online desktop: its session id and the name the server gives it. */
data class Device(val sessionId: String, val name: String)

/** The TV's own session: the entry carrying its `DeviceId`. */
fun ownSessionId(sessions: List<SessionInfoDto>, deviceId: String): String? = sessions.firstOrNull { it.deviceId == deviceId }?.id

/** Desktops that can be followed: Viewer sessions declared controllable, ours left out. */
fun desktops(sessions: List<SessionInfoDto>, deviceId: String): List<Device> = sessions
    .filter { it.client == DESKTOP_CLIENT && it.supportsRemoteControl && it.deviceId != deviceId }
    .map { Device(it.id, displayName(it)) }

private val relayJson = Json

/**
 * The string of a `GeneralCommand` `SendString` frame, or null for any other frame:
 * other commands and `Playstate` from Emby's dashboard are ignored.
 */
fun sendStringOf(frame: String): String? {
    val root = runCatching { relayJson.parseToJsonElement(frame) }.getOrNull() as? JsonObject ?: return null
    if ((root["MessageType"] as? JsonPrimitive)?.content != "GeneralCommand") return null
    val data = root["Data"] as? JsonObject ?: return null
    if ((data["Name"] as? JsonPrimitive)?.content != "SendString") return null
    val value = (data["Arguments"] as? JsonObject)?.get("String") as? JsonPrimitive ?: return null
    return value.content.takeIf { value.isString }
}
