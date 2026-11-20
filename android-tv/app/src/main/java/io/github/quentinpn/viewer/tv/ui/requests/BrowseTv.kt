package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.Centered
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.Retry
import io.github.quentinpn.viewer.tv.request.BrowseFilters
import io.github.quentinpn.viewer.tv.request.Genre
import io.github.quentinpn.viewer.tv.request.MediaSummary
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.RequestException
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.genreChoices
import io.github.quentinpn.viewer.tv.request.genreLabel
import io.github.quentinpn.viewer.tv.request.key
import io.github.quentinpn.viewer.tv.request.ratingChoices
import io.github.quentinpn.viewer.tv.request.ratingLabel
import io.github.quentinpn.viewer.tv.request.sameGenres
import io.github.quentinpn.viewer.tv.request.sameRating
import io.github.quentinpn.viewer.tv.request.sameSort
import io.github.quentinpn.viewer.tv.request.sameYears
import io.github.quentinpn.viewer.tv.request.selectedChoice
import io.github.quentinpn.viewer.tv.request.sortChoices
import io.github.quentinpn.viewer.tv.request.sortLabel
import io.github.quentinpn.viewer.tv.request.yearChoices
import io.github.quentinpn.viewer.tv.request.yearsLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Cards left below the last one shown when the next page is asked for. */
private const val LOAD_AHEAD = 12

private const val GRID_COLUMNS = 6

/** One grid of Films or Séries: its filters, the pages read so far and the scroll, kept in the [RequestsMemory]. */
internal class BrowseState(val type: MediaType, filters: BrowseFilters = BrowseFilters()) {
    var filters by mutableStateOf(filters)
        private set
    val pager = Pager<MediaSummary>(MediaSummary::key)
    var genres by mutableStateOf<List<Genre>?>(null)
    val grid = LazyGridState()

    private fun fetcher(repository: RequestRepository): suspend (Int) -> Fetched<MediaSummary> = { page ->
        repository.browse(type, filters, page).let { Fetched(it.totalPages, it.results) }
    }

    /** Starts over with [next]. */
    fun apply(next: BrowseFilters, repository: RequestRepository, scope: CoroutineScope) {
        filters = next
        pager.reset(scope, fetcher(repository))
    }

    fun more(repository: RequestRepository, scope: CoroutineScope) = pager.more(scope, fetcher(repository))

    fun retry(repository: RequestRepository, scope: CoroutineScope) = pager.retry(scope, fetcher(repository))
}

private enum class Pill(val title: String) { Sort("Tri"), Genre("Genre"), Years("Années"), Rating("Note") }

/**
 * Parcourir (ADR 0125): the filter pills, each opening a list to pick from, over a six-column
 * grid of posters that reads the next page as the focus nears its end.
 */
@Composable
internal fun BrowseTv(
    repository: RequestRepository,
    memory: RequestsMemory,
    type: MediaType,
    art: RequestArt,
    /** The first filter pill: where Down from the segments lands. */
    firstPill: FocusRequester,
    onOpen: (MediaSummary) -> Unit,
) {
    val state = memory.browse.getOrPut(type) { BrowseState(type) }
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<Pill?>(null) }
    val restore = remember { FocusRequester() }

    // First visit, or the statuses changed since (a request was made): read from the first page again
    LaunchedEffect(state, memory.revision) {
        if (state.pager.stamp != memory.revision) {
            state.pager.stamp = memory.revision
            state.apply(state.filters, repository, scope)
        } else if (state.pager.untouched) {
            state.more(repository, scope)
        }
    }
    LaunchedEffect(state) {
        if (state.genres == null) {
            try {
                state.genres = repository.genres(type)
            } catch (_: RequestException) {
                // The genre pill stays off; the other filters work
            }
        }
    }
    LoadAhead(state, state.grid, state.pager) { state.more(repository, scope) }
    FocusRestore(memory, state.pager.items.isNotEmpty(), restore)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val genres = state.genres.orEmpty()
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val f = state.filters
            FilterPill("Tri : ${sortLabel(f)}", Modifier.focusRequester(firstPill)) { choosing = Pill.Sort }
            FilterPill("Genre : ${genreLabel(f, genres)}", enabled = state.genres != null) { choosing = Pill.Genre }
            FilterPill("Années : ${yearsLabel(f)}") { choosing = Pill.Years }
            FilterPill("Note : ${ratingLabel(f)}") { choosing = Pill.Rating }
            if (f != BrowseFilters()) {
                FilterPill("Réinitialiser") {
                    state.apply(BrowseFilters(), repository, scope)
                    scope.launch { state.grid.scrollToItem(0) }
                }
            }
        }
        val pager = state.pager
        val error = pager.error
        when {
            pager.items.isEmpty() && pager.loading -> Centered("Chargement…")
            pager.items.isEmpty() && error != null -> Retry(error) { state.retry(repository, scope) }
            pager.items.isEmpty() && pager.finished -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun titre ne correspond à ces filtres", color = Palette.textSecondary, textAlign = TextAlign.Center)
            }
            else -> MediaGridTv(pager, state.grid, art, memory, restore, onRetry = { state.retry(repository, scope) }, onOpen = onOpen)
        }
    }

    choosing?.let { pill ->
        val f = state.filters
        val (choices, same) = when (pill) {
            Pill.Sort -> sortChoices() to ::sameSort
            Pill.Genre -> genreChoices(state.genres.orEmpty()) to ::sameGenres
            Pill.Years -> yearChoices() to ::sameYears
            Pill.Rating -> ratingChoices() to ::sameRating
        }
        ChoiceDialog(
            title = pill.title,
            labels = choices.map { it.label },
            selected = selectedChoice(choices, f, same),
            onPick = { index ->
                choosing = null
                state.apply(choices[index].apply(f), repository, scope)
                scope.launch { state.grid.scrollToItem(0) }
            },
            onDismiss = { choosing = null },
        )
    }
}

/**
 * The six-column grid of posters of a [pager] (Parcourir, the search of Demander); the card
 * [RequestsMemory.opened] holds [restore]. A later page that failed keeps the cards, with a retry.
 */
@Composable
internal fun MediaGridTv(
    pager: Pager<MediaSummary>,
    grid: LazyGridState,
    art: RequestArt,
    memory: RequestsMemory,
    restore: FocusRequester,
    onRetry: () -> Unit,
    onOpen: (MediaSummary) -> Unit,
) {
    val error = pager.error
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = grid,
        // Room around the cards for the focused one's scale and ring
        contentPadding = PaddingValues(start = 48.dp, end = 44.dp, top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(pager.items, key = { it.key() }) { media ->
            MediaCardTv(
                media,
                art,
                modifier = if (media.key() == memory.opened) Modifier.focusRequester(restore) else Modifier,
                showTitle = true,
            ) { onOpen(media) }
        }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { Retry(error, onRetry) }
    }
}

/** Reads the next page of [pager] once the focus nears the end of [grid]. */
@Composable
internal fun LoadAhead(key: Any, grid: LazyGridState, pager: Pager<MediaSummary>, more: () -> Unit) {
    LaunchedEffect(key) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (last >= pager.items.size - LOAD_AHEAD) more()
        }
    }
}

@Composable
private fun FilterPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text, fontSize = 14.sp, maxLines = 1) }
}
