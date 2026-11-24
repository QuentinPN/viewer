package io.github.quentinpn.viewer.tv.ui.stremio

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.AppServices
import io.github.quentinpn.viewer.tv.PlayerActivity
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.stremio.AddonStreams
import io.github.quentinpn.viewer.tv.stremio.ResumeStore
import io.github.quentinpn.viewer.tv.stremio.SourceList
import io.github.quentinpn.viewer.tv.stremio.StreamTarget
import io.github.quentinpn.viewer.tv.stremio.StremioException
import io.github.quentinpn.viewer.tv.stremio.StremioRepository
import io.github.quentinpn.viewer.tv.stremio.StremioState
import io.github.quentinpn.viewer.tv.stremio.canRetry
import io.github.quentinpn.viewer.tv.stremio.embyTarget
import io.github.quentinpn.viewer.tv.stremio.externalTarget
import io.github.quentinpn.viewer.tv.stremio.stremioUsable
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.episodeLabel
import io.github.quentinpn.viewer.tv.ui.requests.DialogCard
import io.github.quentinpn.viewer.tv.ui.requests.FocusOnOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An opening of the list: a new one asks the addons again; [notice] shows over it. */
internal class SourcesOpening(val notice: String?)

/** What a list asks the addons for ([target], read when the list opens) and what the player shows over the stream. */
internal class SourcesSource(val target: suspend () -> StreamTarget, val title: String, val detail: String?)

/** The list of sources of one [SourcesSource], opened by [open], closed by [SourcesHost]. */
@Stable
internal class SourcesLauncher {
    var source by mutableStateOf<SourcesSource?>(null)
        private set
    var opening by mutableStateOf<SourcesOpening?>(null)
        private set

    fun open(source: SourcesSource) {
        this.source = source
        opening = SourcesOpening(null)
    }

    fun reopen(notice: String) {
        if (source != null) opening = SourcesOpening(notice)
    }

    fun close() {
        opening = null
    }
}

/** Whether a Stremio account is linked (or expired, which the list then says). Read without holding the page: false until known. */
@Composable
internal fun rememberStremioLinked(repository: StremioRepository): Boolean {
    val state by produceState<StremioState?>(null, repository) { value = repository.status().state }
    return stremioUsable(state)
}

/**
 * « Autres sources » on an Emby film or episode, once a Stremio account is linked (or its session expired,
 * which the list then says). A stream plays in [PlayerActivity] by its opaque id; one that fails to open or
 * breaks off brings the list back, read anew, with [SOURCE_FAILED] over it. Never the first button of its row.
 */
@Composable
internal fun SourcesButton(item: BaseItemDto, client: EmbyClient, userId: String, onSessionExpired: () -> Unit) {
    val repository = AppServices.stremio(LocalContext.current)
    val linked = rememberStremioLinked(repository)
    val launcher = remember { SourcesLauncher() }
    val button = remember { FocusRequester() }
    var refocus by remember { mutableIntStateOf(0) }
    if (!linked) return
    OutlinedButton(
        onClick = {
            val (title, detail) = playerTitle(item)
            launcher.open(SourcesSource({ stremioTarget(item, client, userId) }, title, detail))
        },
        modifier = Modifier.focusRequester(button),
    ) { Text("Autres sources") }
    SourcesHost(launcher, repository, onSessionExpired) { refocus++ }
    // Back on the page, from the list or the player: the focus returns to the button, one frame later
    if (refocus > 0) FocusOnOpen(button, refocus)
}

/**
 * The list of [launcher] and the player a stream of it opens. [onClosed] runs when the list goes, to play or
 * to dismiss: the page puts its focus back. The player's result reaches this page only: another page, or none, drops it.
 */
@Composable
internal fun SourcesHost(launcher: SourcesLauncher, repository: StremioRepository, onSessionExpired: () -> Unit, onClosed: () -> Unit) {
    val context = LocalContext.current
    val player = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == PlayerActivity.RESULT_SOURCE_FAILED) launcher.reopen(SOURCE_FAILED)
    }
    val opening = launcher.opening
    val source = launcher.source
    if (opening == null || source == null) return
    SourcesDialog(
        opening,
        repository,
        AppServices.stremioResume(context),
        target = source.target,
        onSessionExpired = onSessionExpired,
        onPlay = { row, resume ->
            launcher.close()
            onClosed()
            player.launch(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_STREMIO_STREAM, row.id)
                    .putExtra(PlayerActivity.EXTRA_STREMIO_RESUME, resume)
                    .putExtra(PlayerActivity.EXTRA_TITLE, source.title)
                    .putExtra(PlayerActivity.EXTRA_DETAIL, source.detail),
            )
        },
        onDismiss = {
            launcher.close()
            onClosed()
        },
    )
}

/** The title the player shows, as for an Emby playback: the film and its year, the series and the episode. */
private fun playerTitle(item: BaseItemDto): Pair<String, String?> =
    if (item.type == "Episode") {
        (item.seriesName ?: item.name) to episodeLabel(item.parentIndexNumber, item.indexNumber, item.name)
    } else {
        item.name to item.productionYear?.toString()
    }

/**
 * The source of a title outside Emby, as the desktop's `ExternalTarget`: a film by its [name]; an episode
 * under its series' [name], « Épisode N » with its numbers. A target the addons cannot name shows in the list.
 */
internal fun externalSource(imdb: String, name: String, season: Int? = null, episode: Int? = null): SourcesSource {
    val target = suspend { externalTarget(imdb, season, episode) }
    return if (season != null && episode != null) {
        SourcesSource(target, name, episodeLabel(season, episode, "Épisode $episode"))
    } else {
        SourcesSource(target, name, null)
    }
}

/**
 * The addons' target for [item], read at click time: an episode needs its series' IMDb id, so the series is
 * read then, never with the page. A series not found counts as one without an IMDb id.
 */
private suspend fun stremioTarget(item: BaseItemDto, client: EmbyClient, userId: String): StreamTarget {
    val series = item.seriesId?.takeIf { item.type == "Episode" }?.let { id ->
        try {
            client.fullItem(userId, id)
        } catch (e: EmbyException) {
            if (e.error != EmbyError.NotFound) throw e
            null
        }
    }
    return embyTarget(item, series)
}

private sealed interface Phase {
    data object Searching : Phase

    data class Failed(val message: String, val retry: Boolean) : Phase

    data class Listed(val list: SourceList) : Phase
}

@Composable
private fun SourcesDialog(
    opening: SourcesOpening,
    repository: StremioRepository,
    resumes: ResumeStore,
    target: suspend () -> StreamTarget,
    onSessionExpired: () -> Unit,
    onPlay: (row: StreamRow, resume: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var phase by remember(opening) { mutableStateOf<Phase>(Phase.Searching) }
    // Bumped by « Réessayer »: a new list, read anew
    var attempt by remember(opening) { mutableIntStateOf(0) }
    // Where the title was left off, read with each list (a stream cut short just saved it); null: no choice
    var resumeText by remember(opening, attempt) { mutableStateOf<String?>(null) }
    var fromStart by remember(opening, attempt) { mutableStateOf(false) }
    val close = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    var firstFocused by remember(opening, attempt) { mutableStateOf(false) }
    LaunchedEffect(opening, attempt) {
        phase = Phase.Searching
        try {
            val content = target()
            resumeText = resumeLabel(withContext(Dispatchers.IO) { resumes.get(content.id) })
            // Each collection opens one list: collected once per opening
            repository.sources(content).collect { list ->
                val expired = expiredList(list)
                phase = if (expired != null) Phase.Failed(expired.message, retry = true) else Phase.Listed(list)
            }
        } catch (e: StremioException) {
            phase = Phase.Failed(e.error.message, canRetry(e.error.kind))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            phase = Phase.Failed(e.error.message, retry = e.error != EmbyError.SessionExpired)
        }
    }
    DialogCard("Autres sources", onDismiss, width = 720.dp) {
        opening.notice?.let { Text(it, color = Palette.warning) }
        when (val p = phase) {
            Phase.Searching -> Text("Recherche des addons…", color = Palette.textSecondary)
            is Phase.Failed -> {
                Text(p.message, color = Palette.text)
                if (p.retry) OutlinedButton(onClick = { attempt++ }) { Text("Réessayer") }
            }
            is Phase.Listed -> {
                val sections = remember(p.list) { sourceSections(p.list) }
                if (sections.isEmpty()) {
                    Text("Aucun addon Stremio ne propose ce type de titre.", color = Palette.textSecondary)
                } else {
                    val firstRow = sections.firstNotNullOfOrNull { it.rows.firstOrNull() }?.id
                    resumeText?.let { label -> ResumeChoice(label, fromStart) { fromStart = it } }
                    // The choice takes its row from the list: the dialog stays the same height
                    val listHeight = if (resumeText != null) LIST_HEIGHT - CHOICE_ROW else LIST_HEIGHT
                    SectionList(sections, firstRow, first, listHeight) { row -> onPlay(row, resumeText != null && !fromStart) }
                    // Once, on the first stream to arrive; later answers never move the focus
                    if (firstRow != null && !firstFocused) {
                        LaunchedEffect(firstRow) {
                            withFrameNanos {}
                            runCatching { first.requestFocus() }
                            firstFocused = true
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(close)) { Text("Fermer") }
        }
    }
    // Until a stream shows, and when the list goes (Réessayer, an expired list), « Fermer » holds the focus.
    // Keyed on the rows, not the phase: a list arriving within the frame must not cancel it
    val hasRows = (phase as? Phase.Listed)?.list?.addons?.any { (it.streams as? AddonStreams.Ready)?.streams?.isNotEmpty() == true } == true
    LaunchedEffect(opening, attempt, phase is Phase.Failed, hasRows) {
        if (hasRows) return@LaunchedEffect
        withFrameNanos {}
        runCatching { close.requestFocus() }
    }
}

private val LIST_HEIGHT = 420.dp

/** GlassButton's height and the column's spacing. */
private val CHOICE_ROW = 48.dp

/** « Reprendre à … » (the default) or « Depuis le début », over the list: the one chosen stays lit. */
@Composable
private fun ResumeChoice(label: String, fromStart: Boolean, onChoice: (fromStart: Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton(onClick = { onChoice(false) }, modifier = Modifier.semantics { selected = !fromStart }, selected = !fromStart) { Text(label) }
        GlassButton(onClick = { onChoice(true) }, modifier = Modifier.semantics { selected = fromStart }, selected = fromStart) { Text(RESTART_LABEL) }
    }
}

@Composable
private fun SectionList(sections: List<SourceSection>, firstRow: String?, first: FocusRequester, maxHeight: Dp, onPlay: (StreamRow) -> Unit) {
    LazyColumn(
        Modifier.heightIn(max = maxHeight),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (section in sections) {
            // Keyed: an addon answering above the focused row does not take its focus away
            item(key = "addon-${section.index}") {
                Text(
                    section.name,
                    color = Palette.textSecondary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp),
                )
            }
            items(section.rows, key = { it.id }) { row ->
                ListItem(
                    selected = false,
                    onClick = { onPlay(row) },
                    headlineContent = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (row.details.isEmpty()) {
                        null
                    } else {
                        { Column { row.details.forEach { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
                    },
                    trailingContent = row.size?.let { size -> { Text(size) } },
                    modifier = if (row.id == firstRow) Modifier.focusRequester(first) else Modifier,
                )
            }
            items(section.notes.size, key = { "note-${section.index}-$it" }) { i ->
                Text(section.notes[i], color = Palette.textSecondary, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
