package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.AppServices
import io.github.quentinpn.viewer.tv.sync.Device
import io.github.quentinpn.viewer.tv.sync.NO_DESKTOP_MESSAGE
import io.github.quentinpn.viewer.tv.sync.SyncHub

const val WATCH_WITH_DESKTOP = "Regarder avec l'ordi"

/**
 * The desktops online, read again when it opens; OK on one sends `join` (ADR 0124).
 * Shared by the film and episode pages and the player.
 */
@Composable
fun LinkPicker(hub: SyncHub, onDismiss: () -> Unit) {
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    LaunchedEffect(hub) { devices = hub.devices() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(420.dp).background(Palette.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(WATCH_WITH_DESKTOP, style = MaterialTheme.typography.titleMedium, color = Palette.text)
            val first = remember { FocusRequester() }
            when (val list = devices) {
                null -> Text("Recherche des ordis…", color = Palette.textSecondary)
                emptyList<Device>() -> {
                    Text(NO_DESKTOP_MESSAGE, color = Palette.textSecondary)
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(first)) { Text("Fermer") }
                }
                else -> list.forEachIndexed { index, device ->
                    Button(
                        onClick = {
                            hub.join(device)
                            onDismiss()
                        },
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    ) { Text(device.name) }
                }
            }
            LaunchedEffect(devices) { if (devices != null) runCatching { first.requestFocus() } }
        }
    }
}

/** « Regarder avec l'ordi » on a detail page, with the last link message below it. */
@Composable
fun WatchWithDesktopButton() {
    val hub = AppServices.sync(LocalContext.current)
    val view by hub.view.collectAsState()
    val notice by hub.notice.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            hub.dismissNotice()
            picking = true
        }, enabled = !view.busy) { Text(WATCH_WITH_DESKTOP) }
        notice?.let { Text(it, color = Palette.error) }
    }
    if (picking) LinkPicker(hub) { picking = false }
}
