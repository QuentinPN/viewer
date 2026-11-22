package io.github.quentinpn.viewer.tv.session

import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ServerUrl
import okhttp3.OkHttpClient

/** What OK on a tile of the shelf found. */
sealed interface Reconnect {
    /** The token was accepted: the session opens, the tile takes the server's id and name. */
    data class Opened(val serverId: String, val serverName: String) : Reconnect

    /** 401: the token is no longer valid, the password is asked again. */
    data object Refused : Reconnect

    /** Anything else (unreachable, another server, 5xx…): nothing is dropped, « Réessayer » is offered. */
    data class Failed(val error: EmbyError) : Reconnect
}

/**
 * Checks a saved token at start: the public probe goes without the token,
 * and the token is only sent once the server answering is the one saved (when its id is
 * known); then `GET /Users/{Id}` with the token. Only a 401 means [Reconnect.Refused].
 */
suspend fun reconnect(entry: SavedServer, token: String, device: DeviceIdentity, http: OkHttpClient): Reconnect = try {
    val client = EmbyClient(ServerUrl.parse(entry.server), device, http)
    val info = client.publicInfo()
    if (entry.serverId != null && info.id != entry.serverId) {
        Reconnect.Failed(EmbyError.ServerMismatch)
    } else {
        client.withToken(token).user(entry.userId)
        Reconnect.Opened(info.id, info.serverName)
    }
} catch (e: EmbyException) {
    if (e.error == EmbyError.SessionExpired) Reconnect.Refused else Reconnect.Failed(e.error)
}
