package io.github.quentinpn.viewer.tv.ui.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.ui.Centered
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Load
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.request.DiscoverRow
import io.github.quentinpn.viewer.tv.request.MediaSummary
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.RequestView
import io.github.quentinpn.viewer.tv.request.SeeAll
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.RequestState
import io.github.quentinpn.viewer.tv.request.key

/** The tabs of the Demander screen, in the order of the segments. */
internal enum class RequestsSegment(val label: String) {
    Discover("Découvrir"),
    Films("Films"),
    Series("Séries"),
}

/**
 * State of the Demander tab that outlives a film or series page opened from it (« Lire »), so
 * Back comes back to the same segment, scroll and card. Everything is read again when the tab is opened anew.
 */
class RequestsMemory {
    internal var segment by mutableStateOf(RequestsSegment.Discover)

    /** Bumped after a request or a cancellation: what shows requests (badges, strip, count) is read again. */
    internal var revision by mutableIntStateOf(0)

    internal var discover by mutableStateOf<Load<List<DiscoverRow>>>(Load.Loading)
    internal var inFlight by mutableStateOf<List<RequestView>>(emptyList())

    /** Revision and retry the discover rows were read under: shown again as they are, nothing is read. */
    internal var discoverStamp: Pair<Int, Int>? = null
    internal val discoverColumn = LazyListState()
    internal val discoverRows = mutableMapOf<String, LazyListState>()
    internal val browse = mutableMapOf<MediaType, BrowseState>()

    /** True once a « Tout voir » opened a grid: Back goes to Découvrir before leaving the tab. */
    internal var fromSeeAll = false

    /** Key of the card last opened: it takes the focus back on return. */
    internal var opened: String? = null

    /** The levels over the segments, the shown one last. */
    internal var stack by mutableStateOf<List<RequestsPage>>(emptyList())

    /** [from] is the card it was opened from, focused again on Back. */
    internal fun open(page: RequestsPage, from: String? = null) {
        opened = from
        stack = stack + page
    }

    /** « Tout voir » of a row: the grid of its type, on its filters. */
    internal fun openBrowse(seeAll: SeeAll) {
        browse[seeAll.type] = BrowseState(seeAll.type, seeAll.filters).also { it.stamp = -1 }
        segment = if (seeAll.type == MediaType.Movie) RequestsSegment.Films else RequestsSegment.Series
        fromSeeAll = true
    }

    internal fun select(next: RequestsSegment) {
        segment = next
        fromSeeAll = false
    }
}

/** The card [RequestsMemory.opened] takes the focus once [ready], then the memory forgets it. */
@Composable
internal fun FocusRestore(memory: RequestsMemory, ready: Boolean, target: FocusRequester) {
    LaunchedEffect(ready) {
        if (!ready || memory.opened == null) return@LaunchedEffect
        // One frame for the list to place the card, a second for its requester to be attached
        repeat(2) {
            withFrameNanos {}
            runCatching { target.requestFocus() }
        }
        memory.opened = null
    }
}

/**
 * Demander (ADR 0125): the setup states until Request is usable, then its segments. Back
 * closes the tab; the levels inside it (a title, a request) handle Back before it.
 */
@Composable
fun RequestsScreen(
    repository: RequestRepository,
    memory: RequestsMemory,
    images: ImageLoader,
    /** « Lire »: the title's page in Emby, by its type and Emby id. */
    onOpenEmby: (MediaType, String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler {
        when {
            memory.stack.isNotEmpty() -> memory.stack = memory.stack.dropLast(1)
            memory.fromSeeAll -> memory.select(RequestsSegment.Discover)
            else -> onClose()
        }
    }
    val access = rememberRequestsAccess(repository)
    val status = access.status
    val art = remember(images, status?.address) { RequestArt(images, repository.origin()) }
    val page = memory.stack.lastOrNull()
    // A title takes the whole screen, its backdrop included
    if (status?.state == RequestState.Ready && page is RequestsPage.Media) {
        MediaTv(repository, memory, art, page, checkNotNull(status.viewer).capabilities) { title ->
            title.embyItemId?.let { onOpenEmby(title.type, it) }
        }
        return
    }
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Text(
            "Demander",
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.text,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
        )
        when {
            status == null -> Centered("Connexion à Request…")
            status.state == RequestState.Ready -> RequestsSegments(repository, memory, art)
            else -> RequestsSetupTv(status, access)
        }
    }
}

@Composable
private fun RequestsSegments(repository: RequestRepository, memory: RequestsMemory, art: RequestArt) {
    val first = remember { FocusRequester() }
    // The selected segment takes the focus when the tab opens, unless a card is to get it back
    LaunchedEffect(Unit) {
        if (memory.opened != null) return@LaunchedEffect
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
    Row(Modifier.padding(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (segment in RequestsSegment.entries) {
            GlassButton(
                onClick = { memory.select(segment) },
                selected = segment == memory.segment,
                modifier = if (segment == memory.segment) Modifier.focusRequester(first) else Modifier,
            ) { Text(segment.label, fontSize = 14.sp, maxLines = 1) }
        }
    }
    // A title opens on its page; Back finds its card again
    val open: (MediaSummary) -> Unit = { memory.open(RequestsPage.Media(it.type, it.tmdbId), from = it.key()) }
    when (memory.segment) {
        RequestsSegment.Discover -> DiscoverTv(repository, memory, art, open, memory::openBrowse, onOpenRequest = {})
        RequestsSegment.Films -> BrowseTv(repository, memory, MediaType.Movie, art, open)
        RequestsSegment.Series -> BrowseTv(repository, memory, MediaType.Tv, art, open)
    }
}
