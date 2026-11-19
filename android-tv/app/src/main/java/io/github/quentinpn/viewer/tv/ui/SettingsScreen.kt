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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.sync.nextOffset
import io.github.quentinpn.viewer.tv.AppServices
import io.github.quentinpn.viewer.tv.BuildConfig
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.player.AccountTracks
import io.github.quentinpn.viewer.tv.player.LANGUAGE_CHOICES
import io.github.quentinpn.viewer.tv.player.LanguageOverrides
import io.github.quentinpn.viewer.tv.player.SUBTITLE_MODE_CHOICES
import io.github.quentinpn.viewer.tv.player.TrackPreferenceStore
import io.github.quentinpn.viewer.tv.player.TrackType
import io.github.quentinpn.viewer.tv.player.accountLabel
import io.github.quentinpn.viewer.tv.player.languageLabel
import io.github.quentinpn.viewer.tv.player.nextOverride
import io.github.quentinpn.viewer.tv.player.subtitleModeLabel
import io.github.quentinpn.viewer.tv.settings.CacheClearing
import io.github.quentinpn.viewer.tv.settings.DeviceReport
import io.github.quentinpn.viewer.tv.settings.ImageCacheUsage
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
import io.github.quentinpn.viewer.tv.settings.usageLabel
import io.github.quentinpn.viewer.tv.update.CheckActivity
import io.github.quentinpn.viewer.tv.update.CheckResult
import io.github.quentinpn.viewer.tv.update.UpdatePrompt
import io.github.quentinpn.viewer.tv.update.Updater
import io.github.quentinpn.viewer.tv.update.readyOf
import io.github.quentinpn.viewer.tv.ui.requests.RequestsSettingsDialogs
import io.github.quentinpn.viewer.tv.ui.requests.rememberRequestsSettings
import io.github.quentinpn.viewer.tv.ui.requests.requestsSettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Réglages (ADR 0051): the Windows playback settings that make sense on a TV, kept
 * on the device for this Emby user. Each row changes on OK and is saved at once.
 * « Accueil » shows or hides each home row (ADR 0094); a long OK moves one (ADR 0114).
 * « Mises à jour » checks for an update on demand and installs it (ADR 0112). « Stockage » shows and clears the
 * image cache (ADR 0080); « À propos » ends it with read-only diagnostics (ADR 0076).
 */
@Composable
fun SettingsScreen(
    store: PlaybackSettingsStore,
    localAddress: Boolean,
    client: EmbyClient,
    images: ImageLoader,
    userId: String,
    rowStore: HomeRowStore,
) {
    var settings by remember { mutableStateOf(store.load()) }
    var rows by remember { mutableStateOf(rowStore.load()) }
    var order by remember { mutableStateOf(rowStore.loadOrder()) }
    // The row being moved with ▲ ▼ (ADR 0114); null when none is
    var moving by remember { mutableStateOf<String?>(null) }
    val still = reducedMotion()
    val update by Updater.state.collectAsState()
    val updateActivity by Updater.activity.collectAsState()
    var checkResult by remember { mutableStateOf<CheckResult?>(null) }
    var updateCard by remember { mutableStateOf(false) }
    // The libraries of the home's « Derniers ajouts » rows, from the same views query
    val homeRowList by produceState<HomeRowList>(HomeRowList.Loading, client, userId) {
        value = try {
            HomeRowList.Loaded(homeRows(homeLibraries(client.views(userId).items)))
        } catch (e: EmbyException) {
            // The fixed rows can still be set; only the libraries' rows are missing
            HomeRowList.Failed(homeRows(emptyList()), e.error.message)
        }
    }
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
    // Disk reads stay off the main thread: the cache's journal is read on first use
    var cache by remember { mutableStateOf<ImageCacheUsage>(ImageCacheUsage.Measuring) }
    var clearing by remember { mutableStateOf(CacheClearing.Idle) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(images) { cache = withContext(Dispatchers.IO) { measure(images) } }
    val first = remember { FocusRequester() }
    val requestsSettings = rememberRequestsSettings(AppServices.requests(context))
    fun change(next: PlaybackSettings) {
        settings = next
        store.save(next)
    }
    fun change(next: SubtitleStyle) = change(settings.copy(subtitles = next))
    // What the Emby account says, shown next to « Comme le compte Emby » (ADR 0120)
    val account by produceState<AccountTracks?>(null, client, userId) { value = AppServices.accountTracks(client, userId) }
    val trackMemory = remember(userId) { TrackPreferenceStore(context, userId) }
    val languages = settings.languages
    fun changeLanguages(type: TrackType, next: LanguageOverrides) {
        // The pick remembered from the player would hide the new choice: it is forgotten
        trackMemory.clear(type)
        change(settings.copy(languages = next))
    }
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
                    "sont envoyés. Jamais ton compte ni l'adresse du serveur.",
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
                    "de chez toi, quand la connexion ne suit pas. OK passe au débit suivant.",
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
        item {
            Choice(
                "Décalage TV",
                "Avec l'ordi, la TV joue un peu en retard pour rattraper le délai de son image. OK ajoute 100 ms, jusqu'à 1500 ms.",
                "${settings.syncOffsetMs} ms",
            ) { change(settings.copy(syncOffsetMs = nextOffset(settings.syncOffsetMs))) }
        }

        item { Section("Langues") }
        item {
            Note(
                "Par défaut, l'app suit les préférences de ton compte Emby. Un choix fait ici ne vaut que sur cet " +
                    "appareil et passe avant celles du compte. OK passe à la valeur suivante.",
            )
        }
        item {
            Choice("Langue audio", null, languages.audioLanguage?.let(::languageLabel) ?: accountLabel(account?.audioLanguages?.map(::languageLabel))) {
                changeLanguages(TrackType.Audio, languages.copy(audioLanguage = nextOverride(languages.audioLanguage, LANGUAGE_CHOICES)))
            }
        }
        item {
            Choice(
                "Mode des sous-titres",
                "« Intelligent » : sous-titres quand l'audio n'est pas dans ta langue, sinon les forcés seulement.",
                languages.subtitleMode?.let(::subtitleModeLabel) ?: accountLabel(account?.let { listOfNotNull(it.subtitleMode?.let(::subtitleModeLabel)) }),
            ) {
                changeLanguages(TrackType.Subtitle, languages.copy(subtitleMode = nextOverride(languages.subtitleMode, SUBTITLE_MODE_CHOICES)))
            }
        }
        item {
            Choice("Langue des sous-titres", null, languages.subtitleLanguage?.let(::languageLabel) ?: accountLabel(account?.subtitleLanguages?.map(::languageLabel))) {
                changeLanguages(TrackType.Subtitle, languages.copy(subtitleLanguage = nextOverride(languages.subtitleLanguage, LANGUAGE_CHOICES)))
            }
        }
        item { Note("Une piste choisie pendant la lecture reste prioritaire, jusqu'à ce que tu changes le réglage correspondant ici.") }

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

        item { Section("Accueil") }
        item {
            Note(
                "Les rangées affichées sur l'accueil, sous le bandeau. OK affiche ou masque une rangée ; une rangée " +
                    "masquée n'est plus demandée au serveur. Un appui long sur OK la déplace : ▲ ▼ la montent ou la " +
                    "descendent, OK ou Retour la posent. Le changement s'applique au retour à l'accueil.",
            )
        }
        when (val list = homeRowList) {
            HomeRowList.Loading -> item { Info(InfoRow("Bibliothèques", "Chargement…")) }
            is HomeRowList.Loaded, is HomeRowList.Failed -> {
                items(arrangedRows(list.rows, order), key = { "home-" + it.key }) { row ->
                    val isMoving = moving == row.key
                    Choice(
                        row.label,
                        if (isMoving) "▲ ▼ pour déplacer, OK ou Retour pour poser" else null,
                        if (isMoving) "Déplacement" else rowLabel(rows.shows(row.key)),
                        (if (still) Modifier else Modifier.animateItem())
                            .onPreviewKeyEvent { event ->
                                if (!isMoving) return@onPreviewKeyEvent false
                                val down = event.type == KeyEventType.KeyDown
                                when (event.key) {
                                    Key.DirectionUp, Key.DirectionDown -> {
                                        val delta = if (event.key == Key.DirectionUp) -1 else 1
                                        if (down) order = rowStore.move(list.rows, row.key, delta)
                                    }
                                    // The long press's own repeats and release do not end the move
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> {
                                        if (down && event.nativeKeyEvent.repeatCount == 0) moving = null
                                    }
                                    // Focus stays on the row being moved
                                    Key.DirectionLeft, Key.DirectionRight -> Unit
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                        selected = isMoving,
                        onLongClick = { moving = row.key },
                    ) { rows = rowStore.toggle(row.key) }
                }
                if (list is HomeRowList.Failed) item { Note("Bibliothèques illisibles : " + list.message) }
            }
        }

        requestsSettingsSection(requestsSettings)

        item { Section("Mises à jour") }
        item {
            Note(
                "Viewer cherche lui-même une mise à jour au lancement puis toutes les 6 heures, sur le réseau " +
                    "de la maison. Tu peux aussi lancer la recherche maintenant.",
            )
        }
        item { Info(InfoRow("Version installée", BuildConfig.VERSION_NAME)) }
        item {
            Choice("Rechercher une mise à jour", null, updateStatus(updateActivity, checkResult, readyOf(update)?.versionName)) {
                if (updateActivity == null) {
                    checkResult = null
                    scope.launch { checkResult = Updater.checkNow(context, AppServices.http) }
                }
            }
        }
        readyOf(update)?.let { ready ->
            item {
                Choice("Installer la version ${ready.versionName}", ready.notes.takeIf { it.isNotBlank() }, "") {
                    updateCard = true
                }
            }
        }

        item { Section("Stockage") }
        item {
            Note(
                "Les affiches et images de fond déjà affichées restent sur l'appareil pour revenir plus vite. Les " +
                    "vider ne change rien sur le serveur Emby : elles seront de nouveau téléchargées à l'affichage.",
            )
        }
        item { Info(InfoRow("Cache des images", usageLabel(cache))) }
        item {
            Choice("Vider le cache", "Images sur l'appareil et en mémoire.", clearing.label) {
                if (clearing != CacheClearing.Running) {
                    clearing = CacheClearing.Running
                    scope.launch {
                        val cleared = withContext(Dispatchers.IO) {
                            runCatching {
                                images.memoryCache?.clear()
                                images.diskCache?.clear()
                            }.isSuccess
                        }
                        cache = withContext(Dispatchers.IO) { measure(images) }
                        clearing = if (cleared) CacheClearing.Done else CacheClearing.Failed
                    }
                }
            }
        }

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
    // The home's card and install flow, « sources inconnues » step included; « Plus tard » here
    // only closes it, the home still offers the update
    if (updateCard) UpdatePrompt { updateCard = false }
    RequestsSettingsDialogs(requestsSettings)
}

private fun onOff(on: Boolean) = if (on) "Activé" else "Désactivé"

/** The status of « Rechercher une mise à jour »: what runs now, else the last manual result. */
internal fun updateStatus(activity: CheckActivity?, result: CheckResult?, readyVersion: String?): String = when {
    activity == CheckActivity.Checking -> "Recherche…"
    activity == CheckActivity.Downloading -> "Téléchargement…"
    readyVersion != null -> "Version $readyVersion prête"
    result == CheckResult.UpToDate -> "Viewer est à jour"
    result is CheckResult.Failed -> result.message
    else -> ""
}

/** The rows of « Accueil »: those of the libraries come from the server. */
private sealed interface HomeRowList {
    val rows: List<HomeRow>

    data object Loading : HomeRowList {
        override val rows = emptyList<HomeRow>()
    }

    data class Loaded(override val rows: List<HomeRow>) : HomeRowList

    data class Failed(override val rows: List<HomeRow>, val message: String) : HomeRowList
}

/** Blocking disk read: call it on [Dispatchers.IO]. */
private fun measure(images: ImageLoader): ImageCacheUsage {
    val disk = images.diskCache ?: return ImageCacheUsage.Unavailable
    return runCatching { ImageCacheUsage.Measured(disk.size, disk.maxSize) }.getOrDefault(ImageCacheUsage.Unavailable)
}

@Composable
internal fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = Palette.text,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Subsection(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.text, modifier = Modifier.padding(top = 12.dp))
}

/** A read-only row: focusable so ▲ ▼ scroll to it, OK does nothing. */
@Composable
internal fun Info(row: InfoRow) {
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
internal fun Note(text: String) {
    Text(text, color = Palette.textSecondary, modifier = Modifier.widthIn(max = 760.dp))
}

/** One focusable row: OK changes [value]. A ListItem stays the same node, so focus stays on it. */
@Composable
internal fun Choice(
    title: String,
    detail: String?,
    value: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { Text(value) },
        modifier = modifier.widthIn(max = 900.dp),
    )
}
