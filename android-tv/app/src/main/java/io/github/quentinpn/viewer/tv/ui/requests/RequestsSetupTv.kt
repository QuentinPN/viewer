package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.request.RequestState
import io.github.quentinpn.viewer.tv.request.RequestLinkStatus

/** Shown under an address in plain `http://`, which the client only accepts on the local network. */
internal const val INSECURE_NOTE = "Adresse non chiffrée (http) : acceptée seulement sur le réseau local."

/**
 * What the Demander tab shows until Request is usable: first connection, signed out, a server
 * too old, unreachable, or one that refused the session. Nothing here connects by itself.
 */
@Composable
internal fun RequestsSetupTv(status: RequestLinkStatus, access: RequestsAccess) {
    val first = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    val change = {
        access.error = null
        editing = true
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 16.dp)) {
        Column(
            Modifier.widthIn(max = 680.dp).background(Palette.surface, RoundedCornerShape(22.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (status.state) {
                RequestState.NotConfigured -> {
                    Heading("Demander des films et des séries")
                    Text(
                        "Ajoute à ta bibliothèque les titres qui n'y sont pas encore. Il suffit de l'adresse de ton serveur Request : " +
                            "ton compte Emby sert de connexion, aucun mot de passe à saisir.",
                        color = Palette.textSecondary,
                    )
                    GlassButton(onClick = change, modifier = Modifier.focusRequester(first), height = BUTTON_HEIGHT) { Text("Se connecter") }
                }
                RequestState.SignedOut -> {
                    Heading("Tu es déconnecté d'Request")
                    Text("Se reconnecter utilise ton compte Emby, sans mot de passe.", color = Palette.textSecondary)
                    GlassButton(onClick = access::signIn, enabled = !access.busy, modifier = Modifier.focusRequester(first), height = BUTTON_HEIGHT) {
                        Text(if (access.busy) "Connexion…" else "Se connecter")
                    }
                }
                RequestState.TooOld -> {
                    Heading("Serveur Request trop ancien")
                    Text(
                        "Ce serveur (API ${status.apiVersion ?: "inconnue"}) ne propose pas tout ce qu'il faut. Il doit être mis à jour.",
                        color = Palette.textSecondary,
                    )
                    if (status.missing.isNotEmpty()) Text("Manque : " + status.missing.joinToString(", "), color = Palette.warning)
                    Retry(access, first, change)
                }
                RequestState.Unreachable -> {
                    Heading("Request est injoignable")
                    Text(status.message ?: "Vérifie l'adresse et le réseau.", color = Palette.textSecondary)
                    Retry(access, first, change)
                }
                RequestState.Rejected -> {
                    Heading("Connexion refusée")
                    Text(status.message ?: "Request a refusé ce compte.", color = Palette.textSecondary)
                    Retry(access, first, change)
                }
                RequestState.Ready -> Unit
            }
            if (status.insecure) Text(INSECURE_NOTE, color = Palette.warning)
            if (!editing) access.error?.let { Text(it, color = Palette.danger) }
        }
    }
    if (editing) {
        AddressDialog(
            initial = status.address.orEmpty(),
            confirmLabel = "Se connecter",
            busy = access.busy,
            error = access.error,
            onSave = { access.saveAddress(it) { editing = false } },
            onDismiss = { editing = false },
        )
    }
    FocusOnOpen(first, status.state)
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Palette.text)
}

@Composable
private fun Retry(access: RequestsAccess, first: FocusRequester, onChangeAddress: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton(onClick = access::refresh, enabled = !access.busy, modifier = Modifier.focusRequester(first), height = BUTTON_HEIGHT) { Text("Réessayer") }
        GlassButton(onClick = onChangeAddress, height = BUTTON_HEIGHT) { Text("Changer d'adresse") }
    }
}
