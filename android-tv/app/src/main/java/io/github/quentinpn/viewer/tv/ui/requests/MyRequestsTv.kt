package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.Centered
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Load
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.Retry
import io.github.quentinpn.viewer.tv.ui.SectionHeader
import io.github.quentinpn.viewer.tv.ui.reducedMotion
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.RequestDetail
import io.github.quentinpn.viewer.tv.request.RequestEvent
import io.github.quentinpn.viewer.tv.request.RequestStatus
import io.github.quentinpn.viewer.tv.request.RequestTotals
import io.github.quentinpn.viewer.tv.request.RequestView
import io.github.quentinpn.viewer.tv.request.StatusFilter
import io.github.quentinpn.viewer.tv.request.Tone
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.eventLabel
import io.github.quentinpn.viewer.tv.request.formatWhen
import io.github.quentinpn.viewer.tv.request.requestLine
import io.github.quentinpn.viewer.tv.request.requestMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cards left below the last row shown when the next page is asked for. */
private const val LOAD_AHEAD = 5

private const val EVENTS_SHOWN = 8

private const val MESSAGE_MS = 4_000L

/** Focus key of a request's row or card: it takes the focus back when its detail is left. */
internal fun requestKey(request: RequestView) = "req:${request.id}"

/** « Mes demandes »: the segment chosen, the pages read so far and the scroll, kept in the [RequestsMemory]. */
internal class MineState {
    var filter by mutableStateOf(StatusFilter.All)
        private set
    val pager = Pager<RequestView> { it.id.toString() }

    /** The member's totals per status, from the last read of any list of requests. */
    var totals by mutableStateOf<RequestTotals?>(null)
    val list = LazyListState()

    private fun fetcher(repository: RequestRepository): suspend (Int) -> Fetched<RequestView> = { page ->
        repository.myRequests(filter.statuses, page).let {
            totals = it.totals
            Fetched(it.totalPages, it.results)
        }
    }

    fun apply(next: StatusFilter, repository: RequestRepository, scope: CoroutineScope) {
        filter = next
        pager.reset(scope, fetcher(repository))
    }

    fun more(repository: RequestRepository, scope: CoroutineScope) = pager.more(scope, fetcher(repository))

    fun retry(repository: RequestRepository, scope: CoroutineScope) = pager.retry(scope, fetcher(repository))
}

/** Mes demandes, full screen: the status segments with their counts, then the member's requests, newest first. OK opens one. */
@Composable
internal fun MyRequestsTv(repository: RequestRepository, memory: RequestsMemory, art: RequestArt, onOpen: (RequestView) -> Unit) {
    val state = memory.mine
    val scope = rememberCoroutineScope()
    val restore = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    LaunchedEffect(state, memory.revision) {
        if (state.pager.stamp != memory.revision) {
            state.pager.stamp = memory.revision
            state.apply(state.filter, repository, scope)
        } else if (state.pager.untouched) {
            state.more(repository, scope)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (last >= state.pager.items.size - LOAD_AHEAD) state.more(repository, scope)
        }
    }
    FocusRestore(memory, state.pager.items.isNotEmpty(), restore)
    // The selected segment takes the focus on opening, unless a row is to get it back
    if (memory.opened == null) FocusOnOpen(first)

    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Text(
            "Mes demandes",
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.text,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
        )
        Row(Modifier.padding(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (filter in StatusFilter.entries) {
                val count = state.totals?.let(filter::count)
                GlassButton(
                    onClick = { if (filter != state.filter) state.apply(filter, repository, scope) },
                    selected = filter == state.filter,
                    modifier = if (filter == state.filter) Modifier.focusRequester(first) else Modifier,
                ) { Text(if (count == null) filter.label else "${filter.label} · $count", fontSize = 14.sp, maxLines = 1) }
            }
        }
        val pager = state.pager
        val error = pager.error
        when {
            pager.items.isEmpty() && pager.loading -> Centered("Chargement…")
            pager.items.isEmpty() && error != null -> Retry(error) { state.retry(repository, scope) }
            pager.items.isEmpty() && pager.finished ->
                Centered(if (state.filter == StatusFilter.All) "Tu n'as encore rien demandé." else "Aucune demande dans cette catégorie.")
            else -> LazyColumn(
                state = state.list,
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(pager.items, key = { it.id }) { request ->
                    RequestRowTv(request, art, if (requestKey(request) == memory.opened) Modifier.focusRequester(restore) else Modifier) { onOpen(request) }
                }
                // A later page that failed: the loaded rows stay, with a way to try again
                if (error != null) item { Retry(error) { state.retry(repository, scope) } }
            }
        }
    }
}

/** One request of the list: its poster, title and meta, and its status. */
@Composable
private fun RequestRowTv(request: RequestView, art: RequestArt, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val media = request.media
    val still = reducedMotion()
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            contentColor = Palette.text,
            focusedContainerColor = Palette.surfaceRaised,
            focusedContentColor = Palette.text,
            pressedContainerColor = Palette.surfaceRaised,
            pressedContentColor = Palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.02f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.accent), shape = shape)),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceRaised)) {
                RequestImage(art, media?.posterPath, "w185", Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(media?.title ?: "Demande n° ${request.id}", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(requestMeta(request), color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1)
            }
            StatusPill(requestLine(request))
        }
    }
}

/**
 * One request in full: poster, title, status, the reason when it was refused or failed, its timeline,
 * « Lire » when the title is in Emby and « Annuler la demande » while it waits (asked first). Read again after a cancellation.
 */
@Composable
internal fun RequestDetailTv(
    repository: RequestRepository,
    memory: RequestsMemory,
    art: RequestArt,
    page: RequestsPage.Detail,
    onPlay: (MediaType, String) -> Unit,
) {
    var detail by remember(page) { mutableStateOf<Load<RequestDetail>>(Load.Loading) }
    var attempt by remember(page) { mutableIntStateOf(0) }
    var confirming by remember(page) { mutableStateOf(false) }
    var busy by remember(page) { mutableStateOf(false) }
    var error by remember(page) { mutableStateOf<String?>(null) }
    var message by remember(page) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    LaunchedEffect(page, attempt) {
        val shown = detail is Load.Ready
        if (!shown) detail = Load.Loading
        val next: Load<RequestDetail> = tried { repository.requestDetail(page.id) }.fold({ Load.Ready(it) }, { Load.Failed(it.requestMessage()) })
        if (!shown || next is Load.Ready) detail = next
    }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(MESSAGE_MS)
        message = null
    }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        when (val d = detail) {
            Load.Loading -> Centered("Chargement…")
            is Load.Failed -> Retry(d.message) { attempt++ }
            is Load.Ready -> {
                val request = d.value.request
                val media = request.media
                val label = requestLine(request)
                Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Palette.surface)) {
                        RequestImage(art, media?.posterPath, "w500", Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(media?.title ?: "Demande n° ${request.id}", style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(label)
                            Text(requestMeta(request), color = Palette.textSecondary)
                        }
                        request.declineReason?.let { Text("Motif du refus : $it", color = Palette.danger) }
                        request.failureReason?.let { Text("Motif de l'échec : $it", color = Palette.danger) }
                        Timeline(d.value.events)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val embyId = media?.embyItemId
                            val cancellable = request.status == RequestStatus.Pending
                            if (embyId != null) Button(onClick = { onPlay(request.type, embyId) }, modifier = Modifier.focusRequester(first)) { Text("Lire") }
                            if (cancellable) {
                                OutlinedButton(
                                    onClick = {
                                        error = null
                                        confirming = true
                                    },
                                    modifier = if (embyId == null) Modifier.focusRequester(first) else Modifier,
                                ) { Text("Annuler la demande") }
                            }
                        }
                        message?.let { Text(it, color = Palette.success) }
                    }
                }
                FocusOnOpen(first)
                if (confirming) {
                    ConfirmDialog(
                        title = "Annuler la demande ?",
                        message = "« ${media?.title ?: "Demande n° ${request.id}"} » ne sera plus demandé.",
                        confirmLabel = "Annuler la demande",
                        dismissLabel = "Garder",
                        busy = busy,
                        error = error,
                        onConfirm = {
                            if (!busy) {
                                busy = true
                                error = null
                                scope.launch {
                                    tried { repository.cancel(request.id) }.fold(
                                        {
                                            confirming = false
                                            message = "Demande annulée"
                                            memory.revision++
                                            attempt++
                                        },
                                        { error = it.requestMessage() },
                                    )
                                    busy = false
                                }
                            }
                        },
                        onDismiss = { confirming = false },
                    )
                }
            }
        }
    }
}

/** The request's events, oldest first: what happened, with its detail, and when and by whom. */
@Composable
private fun Timeline(events: List<RequestEvent>) {
    if (events.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Historique", null)
        for (event in events.takeLast(EVENTS_SHOWN)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).background(eventTone(event.type).color(), CircleShape))
                Column {
                    Text(
                        listOfNotNull(eventLabel(event.type), event.detail).joinToString(" · "),
                        color = Palette.text,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(formatWhen(event.createdAt, time = true), event.actor?.name).joinToString(" · "),
                        color = Palette.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun eventTone(type: String): Tone = when (type) {
    "available" -> Tone.Success
    "declined", "failed" -> Tone.Danger
    "cancelled" -> Tone.Muted
    "requested", "retried" -> Tone.Warning
    else -> Tone.Accent
}
