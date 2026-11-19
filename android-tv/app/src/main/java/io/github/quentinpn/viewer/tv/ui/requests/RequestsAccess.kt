package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.quentinpn.viewer.tv.request.RequestException
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.RequestState
import io.github.quentinpn.viewer.tv.request.RequestLinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The connection to Request as the Demander tab and Réglages both show it: the status (null
 * while it is read), and the actions that change it. Never retries by itself: every call here comes from an OK.
 */
internal class RequestsAccess(val repository: RequestRepository, private val scope: CoroutineScope) {
    var status by mutableStateOf<RequestLinkStatus?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The French message of the last action that failed, shown where the action was made. */
    var error by mutableStateOf<String?>(null)

    fun refresh() = act(clear = true) { repository.status() }

    /** [onDone] runs once the address is saved and read, whatever state the server is in. */
    fun saveAddress(input: String, onDone: () -> Unit) = act { repository.setAddress(input).also { onDone() } }

    fun signIn() = act { repository.signIn() }

    fun signOut() = act {
        repository.signOut()
        repository.status()
    }

    private fun act(clear: Boolean = false, block: suspend () -> RequestLinkStatus) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            if (clear) status = null
            try {
                status = block()
            } catch (e: RequestException) {
                error = e.error.message
            } finally {
                busy = false
            }
        }
    }
}

/** One [RequestsAccess] per screen, reading the status when it opens. */
@Composable
internal fun rememberRequestsAccess(repository: RequestRepository): RequestsAccess {
    val scope = rememberCoroutineScope()
    val access = remember(repository) { RequestsAccess(repository, scope) }
    LaunchedEffect(access) { access.refresh() }
    return access
}

/** The state line of Réglages › Demandes. */
internal fun stateLine(status: RequestLinkStatus?): String = when (status?.state) {
    null -> "Connexion…"
    RequestState.NotConfigured -> "Non configuré"
    RequestState.SignedOut -> "Déconnecté"
    RequestState.TooOld -> "Serveur trop ancien"
    RequestState.Unreachable -> "Injoignable"
    RequestState.Rejected -> "Connexion refusée"
    RequestState.Ready -> "Connecté · ${status.viewer?.name.orEmpty()}"
}
