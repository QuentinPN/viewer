package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import io.github.quentinpn.viewer.tv.emby.cleanSearch
import io.github.quentinpn.viewer.tv.emby.isSearchable
import io.github.quentinpn.viewer.tv.ui.Centered
import io.github.quentinpn.viewer.tv.ui.Retry
import io.github.quentinpn.viewer.tv.ui.SEARCH_DEBOUNCE_MS
import io.github.quentinpn.viewer.tv.ui.SearchField
import io.github.quentinpn.viewer.tv.request.MediaSummary
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The search of Demander: the field, the term its pages answer and the scroll, kept in the [RequestsMemory]. */
internal class RequestSearchState {
    var text by mutableStateOf("")

    /** Cleaned term the [pager] holds the pages of; empty before the first search. */
    var query by mutableStateOf("")
        private set
    val pager = Pager<MediaSummary>(MediaSummary::key)
    val grid = LazyGridState()

    /** Bumped by the keyboard's search key: the term is searched without waiting. */
    var flush by mutableIntStateOf(0)
    var immediate = false

    private fun fetcher(repository: RequestRepository): suspend (Int) -> Fetched<MediaSummary> {
        val asked = query
        return { page -> repository.search(asked, page).let { Fetched(it.totalPages, it.results) } }
    }

    /** Starts over on [next], from its first page. */
    fun start(next: String, repository: RequestRepository, scope: CoroutineScope) {
        query = next
        pager.reset(scope, fetcher(repository))
    }

    fun more(repository: RequestRepository, scope: CoroutineScope) = pager.more(scope, fetcher(repository))

    fun retry(repository: RequestRepository, scope: CoroutineScope) = pager.retry(scope, fetcher(repository))
}

/**
 * Rechercher, a segment of Demander: every title Request finds for the term, those already in
 * Emby included (their page offers « Lire »), in the grid of Parcourir. Searched after a pause in
 * typing or at once on the keyboard's search key; a title opens its page.
 */
@Composable
internal fun SearchRequestsTv(
    repository: RequestRepository,
    memory: RequestsMemory,
    art: RequestArt,
    /** The field: where Down from the segments lands. */
    field: FocusRequester,
    onOpen: (MediaSummary) -> Unit,
) {
    val state = memory.search
    val scope = rememberCoroutineScope()
    val restore = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val query = cleanSearch(state.text)

    LaunchedEffect(query, state.flush, memory.revision) {
        val now = state.immediate
        state.immediate = false
        if (!isSearchable(query)) return@LaunchedEffect
        // Back from a title: the same pages, unless a request changed the statuses since
        if (state.query == query && state.pager.stamp == memory.revision) {
            if (state.pager.untouched) state.more(repository, scope)
            return@LaunchedEffect
        }
        // A new key cancels this effect, and with it the search of an older term
        if (!now) delay(SEARCH_DEBOUNCE_MS)
        val sameTerm = state.query == query
        state.pager.stamp = memory.revision
        state.start(query, repository, scope)
        if (!sameTerm) state.grid.scrollToItem(0)
    }
    LoadAhead(state, state.grid, state.pager) { if (isSearchable(state.query)) state.more(repository, scope) }
    FocusRestore(memory, state.pager.items.isNotEmpty(), restore)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(state.text, { state.text = it }, Modifier.focusRequester(field), placeholder = "Titre d'un film ou d'une série") {
            state.immediate = true
            state.flush++
            keyboard?.hide()
        }
        val pager = state.pager
        val error = pager.error
        when {
            !isSearchable(query) -> Centered("Saisis au moins deux lettres d'un titre de film ou de série.")
            pager.items.isEmpty() && error != null -> Retry(error) { state.retry(repository, scope) }
            // Typed but not searched yet, or on its way
            pager.items.isEmpty() && (state.query != query || pager.loading) -> Centered("Recherche…")
            pager.items.isEmpty() -> Centered("Aucun titre pour « $query ».")
            else -> MediaGridTv(pager, state.grid, art, memory, restore, onRetry = { state.retry(repository, scope) }, onOpen = onOpen)
        }
    }
}
