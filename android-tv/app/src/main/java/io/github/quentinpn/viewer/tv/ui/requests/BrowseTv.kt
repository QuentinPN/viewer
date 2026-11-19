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
import androidx.compose.runtime.mutableStateListOf
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
import io.github.quentinpn.viewer.tv.request.mergeMedia
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Cards left below the last one shown when the next page is asked for. */
private const val LOAD_AHEAD = 12

private const val GRID_COLUMNS = 6

/**
 * One grid of Films or Séries: the filters, the pages read so far and the scroll, kept in the
 * [RequestsMemory] so coming back from a title shows it as it was. An answer for filters no
 * longer in force is dropped, and a page read twice adds nothing.
 */
internal class BrowseState(val type: MediaType, filters: BrowseFilters = BrowseFilters()) {
    var filters by mutableStateOf(filters)
        private set
    val items = mutableStateListOf<MediaSummary>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var genres by mutableStateOf<List<Genre>?>(null)
    val grid = LazyGridState()

    /** The [RequestsMemory.revision] the items were read under. */
    var stamp = -1
    private var page = 0
    private var totalPages = 1
    private var job: Job? = null

    /** Starts over with [next]. */
    fun apply(next: BrowseFilters, repository: RequestRepository, scope: CoroutineScope) {
        job?.cancel()
        filters = next
        items.clear()
        page = 0
        totalPages = 1
        error = null
        loading = false
        more(repository, scope)
    }

    /** The next page, unless one is on its way, the last is read, or the last attempt failed (see [retry]). */
    fun more(repository: RequestRepository, scope: CoroutineScope) {
        if (loading || error != null || page >= totalPages) return
        loading = true
        val wanted = filters
        job = scope.launch {
            try {
                val result = repository.browse(type, wanted, page + 1)
                if (wanted == filters) {
                    val merged = mergeMedia(items, result.results)
                    items.addAll(merged.drop(items.size))
                    page = result.page
                    totalPages = result.totalPages
                }
            } catch (e: RequestException) {
                if (wanted == filters) error = e.error.message
            } finally {
                if (wanted == filters) loading = false
            }
        }
    }

    fun retry(repository: RequestRepository, scope: CoroutineScope) {
        error = null
        more(repository, scope)
    }

    /** Nothing read yet and nothing on its way: the first page was never asked for, or its read was cut short. */
    val untouched: Boolean get() = page == 0 && !loading && error == null

    val finished: Boolean get() = page > 0 && page >= totalPages
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
    onOpen: (MediaSummary) -> Unit,
) {
    val state = memory.browse.getOrPut(type) { BrowseState(type) }
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<Pill?>(null) }
    val restore = remember { FocusRequester() }

    // First visit, or the statuses changed since (a request was made): read from the first page again
    LaunchedEffect(state, memory.revision) {
        if (state.stamp != memory.revision) {
            state.stamp = memory.revision
            state.apply(state.filters, repository, scope)
        } else if (state.untouched) {
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
    LaunchedEffect(state) {
        snapshotFlow { state.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
            if (last >= state.items.size - LOAD_AHEAD) state.more(repository, scope)
        }
    }
    FocusRestore(memory, state.items.isNotEmpty(), restore)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val genres = state.genres.orEmpty()
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val f = state.filters
            FilterPill("Tri : ${sortLabel(f)}") { choosing = Pill.Sort }
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
        val error = state.error
        when {
            state.items.isEmpty() && state.loading -> Centered("Chargement…")
            state.items.isEmpty() && error != null -> Retry(error) { state.retry(repository, scope) }
            state.items.isEmpty() && state.finished -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun titre ne correspond à ces filtres", color = Palette.textSecondary, textAlign = TextAlign.Center)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                state = state.grid,
                // Room around the cards for the focused one's scale and ring
                contentPadding = PaddingValues(start = 48.dp, end = 44.dp, top = 12.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.items, key = { it.key() }) { media ->
                    MediaCardTv(
                        media,
                        art,
                        modifier = if (media.key() == memory.opened) Modifier.focusRequester(restore) else Modifier,
                        showTitle = true,
                    ) { onOpen(media) }
                }
                // A later page that failed: the loaded cards stay, with a way to try again
                if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { Retry(error) { state.retry(repository, scope) } }
            }
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

@Composable
private fun FilterPill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    GlassButton(onClick = onClick, enabled = enabled) { Text(text, fontSize = 14.sp, maxLines = 1) }
}
