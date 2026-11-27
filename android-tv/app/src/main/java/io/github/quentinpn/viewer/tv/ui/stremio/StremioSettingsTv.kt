package io.github.quentinpn.viewer.tv.ui.stremio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.settings.InfoRow
import io.github.quentinpn.viewer.tv.stremio.AddonInfo
import io.github.quentinpn.viewer.tv.stremio.StremioException
import io.github.quentinpn.viewer.tv.stremio.StremioRepository
import io.github.quentinpn.viewer.tv.stremio.StremioState
import io.github.quentinpn.viewer.tv.stremio.StremioStatus
import io.github.quentinpn.viewer.tv.ui.Choice
import io.github.quentinpn.viewer.tv.ui.Field
import io.github.quentinpn.viewer.tv.ui.Info
import io.github.quentinpn.viewer.tv.ui.Note
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.Section
import io.github.quentinpn.viewer.tv.ui.requests.DialogCard
import io.github.quentinpn.viewer.tv.ui.requests.FocusOnOpen
import io.github.quentinpn.viewer.tv.ui.requests.downLeavesField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What Réglages › Stremio shows and edits: the account state (null while it is read), its stream addons,
 * and the sign-in card. One action at a time; never retries by itself.
 */
internal class StremioSettingsState(val repository: StremioRepository, private val scope: CoroutineScope) {
    var status by mutableStateOf<StremioStatus?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The French message of the last action that failed: in the card while it is open, else under the section. */
    var error by mutableStateOf<String?>(null)
    var signingIn by mutableStateOf(false)

    fun refresh() = act { repository.status() }

    /** The password goes to the sign-in call only; the card closes once signed in. */
    fun signIn(email: String, password: String) = act { repository.signIn(email, password).also { signingIn = false } }

    fun signOut() = act { repository.signOut() }

    private fun act(block: suspend () -> StremioStatus) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                status = block()
            } catch (e: StremioException) {
                error = e.error.message
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun rememberStremioSettings(repository: StremioRepository): StremioSettingsState {
    val scope = rememberCoroutineScope()
    val state = remember(repository) { StremioSettingsState(repository, scope) }
    LaunchedEffect(state) { state.refresh() }
    return state
}

/** The state line of Réglages › Stremio, the desktop's. */
internal fun statusLabel(state: StremioState): String = when (state) {
    StremioState.SignedOut -> "Déconnecté"
    StremioState.SignedIn -> "Connecté"
    StremioState.ReauthRequired -> "Session Stremio expirée. Reconnecte-toi."
    StremioState.Unreachable -> "Stremio ne répond pas."
}

/** « Films · Séries »: the kinds an addon serves streams for. */
internal fun addonKinds(addon: AddonInfo): String =
    listOfNotNull("Films".takeIf { addon.movie }, "Séries".takeIf { addon.series }).joinToString(" · ")

/** « Stremio » of Réglages: the state, the stream addons once signed in, then sign in, out or retry. */
internal fun LazyListScope.stremioSettingsSection(state: StremioSettingsState) {
    val status = state.status
    val current = status?.state
    item { Section("Stremio") }
    item { Info(InfoRow("État", current?.let(::statusLabel) ?: "Lecture de l'état…")) }
    if (status != null && current == StremioState.SignedIn) {
        if (status.addons.isEmpty()) {
            item { Note("Aucun addon de flux sur ce compte.") }
        } else {
            // By position: two addons may share a manifest name
            itemsIndexed(status.addons, key = { i, _ -> "stremio-addon-$i" }) { _, addon -> Info(InfoRow(addon.name, addonKinds(addon))) }
        }
    }
    if (current == StremioState.SignedOut || current == StremioState.ReauthRequired) {
        item {
            Choice("Se connecter", null, "") {
                state.error = null
                state.signingIn = true
            }
        }
    }
    if (current == StremioState.SignedIn || current == StremioState.ReauthRequired) {
        item { Choice("Se déconnecter", null, "") { state.signOut() } }
    }
    if (current == StremioState.Unreachable || (status == null && state.error != null)) {
        item { Choice("Réessayer", null, "") { state.refresh() } }
    }
    state.error?.takeIf { !state.signingIn }?.let { item { Note(it) } }
}

/** The sign-in card over Réglages, while it is open. */
@Composable
internal fun StremioSettingsDialogs(state: StremioSettingsState) {
    if (state.signingIn) SignInDialog(state)
}

/** E-mail and password; the password is cleared after every attempt and never saved. */
@Composable
private fun SignInDialog(state: StremioSettingsState) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val canSubmit = !state.busy && email.isNotBlank() && password.isNotEmpty()
    fun submit() {
        if (!canSubmit) return
        val secret = password
        password = ""
        state.signIn(email, secret)
    }
    DialogCard("Stremio", onDismiss = { state.signingIn = false }, width = 560.dp) {
        Field(
            "Adresse e-mail",
            email,
            { email = it },
            KeyboardType.Email,
            Modifier.focusRequester(first).downLeavesField(focusManager),
        )
        Field(
            "Mot de passe",
            password,
            { password = it },
            KeyboardType.Password,
            Modifier.downLeavesField(focusManager),
            secret = true,
            onDone = ::submit,
        )
        state.error?.let { Text(it, color = Palette.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { state.signingIn = false }) { Text("Annuler") }
            Button(onClick = ::submit, enabled = canSubmit) { Text(if (state.busy) "Connexion…" else "Se connecter") }
        }
    }
    FocusOnOpen(first)
}
