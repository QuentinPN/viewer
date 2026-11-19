package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.quentinpn.viewer.tv.settings.InfoRow
import io.github.quentinpn.viewer.tv.ui.Choice
import io.github.quentinpn.viewer.tv.ui.Info
import io.github.quentinpn.viewer.tv.ui.Load
import io.github.quentinpn.viewer.tv.ui.Note
import io.github.quentinpn.viewer.tv.ui.Section
import io.github.quentinpn.viewer.tv.ui.Subsection
import io.github.quentinpn.viewer.tv.request.AuthSession
import io.github.quentinpn.viewer.tv.request.RequestException
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.RequestState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What Réglages › Demandes shows and edits: the connection, the devices signed in, and the cards over the screen. */
internal class RequestsSettingsState(val access: RequestsAccess, private val scope: CoroutineScope) {
    var sessions by mutableStateOf<Load<List<AuthSession>>>(Load.Loading)
    var editingAddress by mutableStateOf(false)
    var revoking by mutableStateOf<AuthSession?>(null)
    var revokeBusy by mutableStateOf(false)
    var revokeError by mutableStateOf<String?>(null)

    suspend fun loadSessions() {
        sessions = try {
            Load.Ready(access.repository.sessions())
        } catch (e: RequestException) {
            Load.Failed(e.error.message)
        }
    }

    fun revoke(session: AuthSession) {
        if (revokeBusy) return
        revokeBusy = true
        revokeError = null
        scope.launch {
            try {
                access.repository.revokeSession(session.id)
                revoking = null
                loadSessions()
            } catch (e: RequestException) {
                revokeError = e.error.message
            } finally {
                revokeBusy = false
            }
        }
    }
}

@Composable
internal fun rememberRequestsSettings(repository: RequestRepository): RequestsSettingsState {
    val scope = rememberCoroutineScope()
    val access = rememberRequestsAccess(repository)
    val state = remember(access) { RequestsSettingsState(access, scope) }
    // The devices are read once the connection is, and again after a sign-in
    LaunchedEffect(access.status?.state) {
        if (access.status?.state == RequestState.Ready) state.loadSessions() else state.sessions = Load.Loading
    }
    return state
}

/** The device's name as the server knows it, else its user agent. */
internal fun sessionName(session: AuthSession): String = session.deviceName ?: session.userAgent ?: "Appareil inconnu"

/** « Demandes » of Réglages: address, state, the devices signed in, then sign out or in. */
internal fun LazyListScope.requestsSettingsSection(state: RequestsSettingsState) {
    val access = state.access
    val status = access.status
    item { Section("Demandes") }
    item {
        Note("Demande à ton serveur Request les films et séries qui manquent. Aucun mot de passe : ton compte Emby sert de connexion.")
    }
    item {
        Choice("Adresse du serveur", null, status?.address ?: "Non configurée") {
            access.error = null
            state.editingAddress = true
        }
    }
    item { Info(InfoRow("État", stateLine(status))) }
    if (status?.insecure == true) item { Note(INSECURE_NOTE) }
    status?.message?.let { item { Note(it) } }
    access.error?.takeIf { !state.editingAddress }?.let { item { Note(it) } }
    when (status?.state) {
        RequestState.Ready -> {
            item { Subsection("Appareils connectés") }
            when (val list = state.sessions) {
                Load.Loading -> item { Info(InfoRow("Appareils", "Chargement…")) }
                is Load.Failed -> item { Note("Appareils illisibles : " + list.message) }
                is Load.Ready -> items(list.value, key = { "ur-session-" + it.id }) { session ->
                    Choice(sessionName(session), null, if (session.current) "cet appareil" else "Déconnecter") {
                        if (!session.current) {
                            state.revokeError = null
                            state.revoking = session
                        }
                    }
                }
            }
            item {
                Choice("Se déconnecter", "Rien ne se reconnecte tant que tu ne l'as pas demandé.", "") { access.signOut() }
            }
        }
        RequestState.SignedOut -> item { Choice("Se connecter", null, "") { access.signIn() } }
        RequestState.TooOld, RequestState.Unreachable, RequestState.Rejected ->
            item { Choice("Réessayer", null, "") { access.refresh() } }
        RequestState.NotConfigured, null -> Unit
    }
}

/** The cards over Réglages: the address to type, and the confirmation before a device is signed out. */
@Composable
internal fun RequestsSettingsDialogs(state: RequestsSettingsState) {
    val access = state.access
    if (state.editingAddress) {
        AddressDialog(
            initial = access.status?.address.orEmpty(),
            confirmLabel = "Enregistrer",
            busy = access.busy,
            error = access.error,
            onSave = { access.saveAddress(it) { state.editingAddress = false } },
            onDismiss = { state.editingAddress = false },
        )
    }
    state.revoking?.let { session ->
        ConfirmDialog(
            title = "Déconnecter cet appareil ?",
            message = "« ${sessionName(session)} » devra se reconnecter pour demander des titres.",
            confirmLabel = "Déconnecter",
            busy = state.revokeBusy,
            error = state.revokeError,
            onConfirm = { state.revoke(session) },
            onDismiss = { state.revoking = null },
        )
    }
}
