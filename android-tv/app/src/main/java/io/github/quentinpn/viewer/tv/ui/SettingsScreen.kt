package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.settings.DeviceReport
import io.github.quentinpn.viewer.tv.settings.InfoRow
import io.github.quentinpn.viewer.tv.settings.PlaybackSettings
import io.github.quentinpn.viewer.tv.settings.PlaybackSettingsStore
import io.github.quentinpn.viewer.tv.settings.ServerAbout
import io.github.quentinpn.viewer.tv.settings.SubtitleBackground
import io.github.quentinpn.viewer.tv.settings.SubtitleColor
import io.github.quentinpn.viewer.tv.settings.SubtitlePosition
import io.github.quentinpn.viewer.tv.settings.SubtitleSize
import io.github.quentinpn.viewer.tv.settings.SubtitleStyle
import io.github.quentinpn.viewer.tv.settings.addressNote
import io.github.quentinpn.viewer.tv.settings.capLabel
import io.github.quentinpn.viewer.tv.settings.decoderRows
import io.github.quentinpn.viewer.tv.settings.displayHdrRow
import io.github.quentinpn.viewer.tv.settings.nextCap
import io.github.quentinpn.viewer.tv.settings.nextChoice
import io.github.quentinpn.viewer.tv.settings.serverAddress
import io.github.quentinpn.viewer.tv.settings.serverRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Réglages (ADR 0051): the Windows playback settings that make sense on a TV, kept
 * on the device for this Emby user. Each row changes on OK and is saved at once.
 * « À propos » ends it with read-only diagnostics (ADR 0076).
 */
@Composable
fun SettingsScreen(store: PlaybackSettingsStore, localAddress: Boolean, client: EmbyClient) {
    var settings by remember { mutableStateOf(store.load()) }
    val context = LocalContext.current
    val app = remember { DeviceReport.appRows(context) }
    val device = remember { DeviceReport.deviceRows() }
    val address = remember(client) { serverAddress(client.server.url) }
    // Once per screen open, cancelled when it closes
    val server by produceState<ServerAbout>(ServerAbout.Loading, client) {
        value = try {
            ServerAbout.Loaded(client.publicInfo())
        } catch (e: EmbyException) {
            ServerAbout.Failed(e.error)
        }
    }
    val playback by produceState<List<InfoRow>?>(null) {
        value = withContext(Dispatchers.Default) {
            runCatching { decoderRows(DeviceReport.decoders()) + displayHdrRow(DeviceReport.displayHdrTypes(context)) }
                .getOrElse { listOf(InfoRow("Décodeurs", "Liste illisible sur cet appareil")) }
        }
    }
    val first = remember { FocusRequester() }
    fun change(next: PlaybackSettings) {
        settings = next
        store.save(next)
    }
    fun change(next: SubtitleStyle) = change(settings.copy(subtitles = next))
    val style = settings.subtitles
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
        item {
            Choice(
                "Mode nuit",
                "Réduit l'écart entre dialogues et explosions, pour regarder à faible volume. Aussi dans le menu ▼ " +
                    "du lecteur. Sans effet quand le son part tel quel vers l'ampli.",
                onOff(settings.nightMode),
            ) { change(settings.copy(nightMode = !settings.nightMode)) }
        }

        item { Section("Sous-titres") }
        item {
            Note(
                "Apparence des sous-titres texte (SRT, WebVTT…). Les sous-titres en image (PGS, DVD) ne changent " +
                    "pas. OK passe à la valeur suivante.",
            )
        }
        item {
            Choice("Taille", null, style.size.label) { change(style.copy(size = nextChoice(style.size, SubtitleSize.entries))) }
        }
        item {
            Choice("Couleur", null, style.color.label) { change(style.copy(color = nextChoice(style.color, SubtitleColor.entries))) }
        }
        item {
            Choice("Lisibilité", null, style.background.label) {
                change(style.copy(background = nextChoice(style.background, SubtitleBackground.entries)))
            }
        }
        item {
            Choice("Position", "Pendant que les commandes s'affichent, les sous-titres passent au-dessus.", style.position.label) {
                change(style.copy(position = nextChoice(style.position, SubtitlePosition.entries)))
            }
        }
        item {
            Choice(
                "Appliquer aussi aux sous-titres stylés (ASS)",
                "Sinon, ils gardent la police et les couleurs prévues par leur auteur ; seule la position suit ce réglage.",
                onOff(style.overrideAss),
            ) { change(style.copy(overrideAss = !style.overrideAss)) }
        }
        item { Note("Ces réglages restent sur cet appareil et s'appliquent à la prochaine lecture.") }

        item { Section("À propos") }
        item { Subsection("Application") }
        items(app, key = { "app-" + it.label }) { Info(it) }
        item { Subsection("Appareil") }
        items(device, key = { "device-" + it.label }) { Info(it) }
        item { Subsection("Serveur") }
        items(serverRows(server, address), key = { "server-" + it.label }) { Info(it) }
        item { Subsection("Lecture") }
        item {
            Note(
                "Décodeurs annoncés par l'appareil et formats HDR annoncés par l'écran : une indication, pas une " +
                    "garantie. La lecture réelle dépend aussi du fichier, du débit et de la chaîne HDMI.",
            )
        }
        val rows = playback
        if (rows == null) {
            item { Info(InfoRow("Décodeurs", "Lecture en cours…")) }
        } else {
            items(rows, key = { "playback-" + it.label }) { Info(it) }
        }
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
private fun Subsection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.text, modifier = Modifier.padding(top = 12.dp))
}

/** A read-only row: focusable so ▲ ▼ scroll to it, OK does nothing. */
@Composable
private fun Info(row: InfoRow) {
    ListItem(
        selected = false,
        onClick = {},
        headlineContent = { Text(row.label) },
        supportingContent = row.detail?.let { { Text(it) } },
        trailingContent = { Text(row.value) },
        modifier = Modifier.widthIn(max = 900.dp),
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
