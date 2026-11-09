package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.settings.PlaybackSettings
import io.github.quentinpn.viewer.tv.settings.PlaybackSettingsStore
import io.github.quentinpn.viewer.tv.settings.addressNote
import io.github.quentinpn.viewer.tv.settings.capLabel
import io.github.quentinpn.viewer.tv.settings.nextCap

/**
 * Réglages (ADR 0051): the Windows playback settings that make sense on a TV, kept
 * on the device for this Emby user. Each row changes on OK and is saved at once.
 */
@Composable
fun SettingsScreen(store: PlaybackSettingsStore, localAddress: Boolean, version: String) {
    var settings by remember { mutableStateOf(store.load()) }
    val first = remember { FocusRequester() }
    fun change(next: PlaybackSettings) {
        settings = next
        store.save(next)
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Palette.background),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Réglages", style = MaterialTheme.typography.headlineMedium, color = Palette.text) }

        item { Section("Intros et génériques") }
        item {
            Note(
                "Pendant une intro ou un générique, un bouton propose de les passer. Les repères viennent d'abord " +
                    "du serveur Emby, puis des bases communautaires IntroDB et TheIntroDB.",
            )
        }
        item {
            Choice(
                "Chercher les intros et génériques sur IntroDB et TheIntroDB",
                "Seuls les identifiants IMDb, TMDB ou TVDB du film ou de la série, la saison, l'épisode et la durée " +
                    "sont envoyés. Jamais votre compte ni l'adresse du serveur.",
                onOff(settings.externalSegments),
                Modifier.focusRequester(first),
            ) { change(settings.copy(externalSegments = !settings.externalSegments)) }
        }
        item {
            Choice(
                "Passer automatiquement",
                "Une seule fois par lecture : revenir en arrière affiche de nouveau le bouton.",
                onOff(settings.autoSkip),
            ) { change(settings.copy(autoSkip = !settings.autoSkip)) }
        }
        item { Note("Données d'intros fournies par IntroDB (introdb.app) et TheIntroDB (theintrodb.org).") }

        item { Section("Qualité de lecture") }
        item {
            Note(
                "Au-delà du débit choisi, le serveur Emby convertit le film à la volée pour le réduire. Utile hors " +
                    "de chez vous, quand la connexion ne suit pas. OK passe au débit suivant.",
            )
        }
        item {
            Choice("Réseau local", null, capLabel(settings.localCap)) {
                change(settings.copy(localCap = nextCap(settings.localCap)))
            }
        }
        item {
            Choice("Accès distant", null, capLabel(settings.remoteCap)) {
                change(settings.copy(remoteCap = nextCap(settings.remoteCap)))
            }
        }
        item { Note(addressNote(localAddress)) }
        item { Note("Ces réglages restent sur cet appareil et s'appliquent à la prochaine lecture.") }

        item { Section("À propos") }
        item { Note("Viewer TV, version $version") }
    }
}

private fun onOff(on: Boolean) = if (on) "Activé" else "Désactivé"

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = Palette.text,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = Palette.textSecondary, modifier = Modifier.widthIn(max = 760.dp))
}

/** One focusable row: OK changes [value]. A ListItem stays the same node, so focus stays on it. */
@Composable
private fun Choice(title: String, detail: String?, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Text(value) },
        modifier = modifier.widthIn(max = 900.dp),
    )
}
