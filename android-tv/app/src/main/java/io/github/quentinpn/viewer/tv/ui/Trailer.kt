package io.github.quentinpn.viewer.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.TrailerAction
import io.github.quentinpn.viewer.tv.emby.trailerAction
import io.github.quentinpn.viewer.tv.emby.trailerChoices
import kotlinx.coroutines.launch

/** How a trailer leaves the page (ADR 0083), wired by `MainActivity`. */
class TrailerHandlers(
    /** Plays a local trailer item in the app's player, without any report. */
    val play: (itemId: String) -> Unit,
    /** Opens a checked address outside the app; false when no app can open it. */
    val open: (url: String) -> Boolean,
)

internal const val TRAILER_NO_APP = "Aucune application ne peut ouvrir cette bande-annonce."
internal const val TRAILER_MISSING = "Bande-annonce introuvable sur le serveur."

/**
 * « Bande-annonce » when [item] has one (ADR 0083): the server's file first, else the web
 * page. Never the first button of its row, so a reload does not move the focus. [onMessage]
 * shows a French message under the row, or clears it with null.
 */
@Composable
internal fun TrailerButton(
    item: BaseItemDto,
    client: EmbyClient,
    userId: String,
    handlers: TrailerHandlers,
    onSessionExpired: () -> Unit,
    onMessage: (String?) -> Unit,
) {
    val choices = remember(item.localTrailerCount, item.remoteTrailers) { trailerChoices(item) }
    if (choices.isEmpty()) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            // A second press while the list is read does nothing
            if (busy) return@OutlinedButton
            busy = true
            onMessage(null)
            scope.launch {
                try {
                    when (val action = trailerAction(choices) { client.localTrailers(userId, item.id) }) {
                        is TrailerAction.Play -> handlers.play(action.itemId)
                        is TrailerAction.Open -> if (!handlers.open(action.url)) onMessage(TRAILER_NO_APP)
                        null -> onMessage(TRAILER_MISSING)
                    }
                } catch (e: EmbyException) {
                    if (e.error == EmbyError.SessionExpired) onSessionExpired() else onMessage(e.error.message)
                } finally {
                    busy = false
                }
            }
        },
    ) {
        Text("Bande-annonce")
    }
}
