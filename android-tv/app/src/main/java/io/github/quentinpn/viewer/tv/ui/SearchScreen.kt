package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.SEARCH_SHOWN
import io.github.quentinpn.viewer.tv.emby.SearchResults
import io.github.quentinpn.viewer.tv.emby.cleanSearch
import io.github.quentinpn.viewer.tv.emby.isSearchable
import io.github.quentinpn.viewer.tv.emby.searchTitles
import kotlinx.coroutines.delay

/** Delay after the last key before searching; the keyboard's search key searches at once. */
internal const val SEARCH_DEBOUNCE_MS = 300L

/** A row of results; [note] says when the server has more than the row shows. */
internal data class SearchGroup(val title: String, val items: List<BaseItemDto>, val landscape: Boolean, val note: String?)

/** Films, then series, then episodes, as on Windows; empty types are left out. */
internal fun searchGroups(results: SearchResults): List<SearchGroup> = listOfNotNull(
    SearchGroup(
        "Films",
        results.movies,
        landscape = false,
        note = if (results.moreMovies) "Seuls les $SEARCH_SHOWN premiers films sont affichés : précisez la recherche." else null,
    ).takeIf { it.items.isNotEmpty() },
    SearchGroup(
        "Séries",
        results.series,
        landscape = false,
        note = if (results.moreSeries) "Seules les $SEARCH_SHOWN premières séries sont affichées : précisez la recherche." else null,
    ).takeIf { it.items.isNotEmpty() },
    SearchGroup(
        "Épisodes",
        results.episodes,
        landscape = true,
        note = if (results.moreEpisodes) "D'autres épisodes peuvent correspondre : précisez la recherche." else null,
    ).takeIf { it.items.isNotEmpty() },
)

internal sealed interface SearchState {
    /** The term is too short to be sent. */
    data object Idle : SearchState

    data object Loading : SearchState

    data class Ready(val results: SearchResults) : SearchState

    data class Failed(val query: String, val message: String) : SearchState
}

/**
 * Search state that outlives the screen while a film or series page is shown, so
 * coming back keeps the term, the results, the scroll and the focused card.
 */
class SearchMemory {
    internal var text by mutableStateOf("")
    internal var state by mutableStateOf<SearchState>(SearchState.Idle)
    internal var attempt by mutableIntStateOf(0)
    internal val column = LazyListState()
    internal val rows = mutableMapOf<String, LazyListState>()

    /** Group title and item id of the card last opened. */
    internal var opened: Pair<String, String>? = null

    /** Cleaned term and attempt the state answers: shown again, nothing is searched. */
    internal var searchedFor: Pair<String, Int>? = null

    /** Bumped by the keyboard's search key: the term is searched without waiting. */
    internal var flush by mutableIntStateOf(0)
    internal var immediate = false
}

/** A text field, then the films, series and episodes whose title starts with the term. */
@Composable
fun SearchScreen(
    client: EmbyClient,
    userId: String,
    images: ImageLoader,
    memory: SearchMemory,
    onOpenSeries: (seriesId: String) -> Unit,
    onOpenMovie: (movieId: String) -> Unit,
    onSessionExpired: () -> Unit,
) {
    val query = cleanSearch(memory.text)
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(query, memory.attempt, memory.flush) {
        val now = memory.immediate
        memory.immediate = false
        if (!isSearchable(query)) {
            memory.state = SearchState.Idle
            memory.searchedFor = null
            return@LaunchedEffect
        }
        val key = query to memory.attempt
        if (memory.searchedFor == key) return@LaunchedEffect
        // A new key cancels this effect, and with it the request of an older term
        if (!now) delay(SEARCH_DEBOUNCE_MS)
        memory.state = SearchState.Loading
        val next = try {
            SearchState.Ready(searchTitles(client, userId, query))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            SearchState.Failed(query, e.error.message)
        }
        // An answer for a term no longer in the field is dropped
        if (cleanSearch(memory.text) != query) return@LaunchedEffect
        memory.state = next
        memory.searchedFor = key
        memory.opened = null
    }

    // Typing first, unless coming back from a page opened from the results
    LaunchedEffect(Unit) {
        if (memory.opened != null && memory.state is SearchState.Ready) return@LaunchedEffect
        withFrameNanos {}
        runCatching { field.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Text(
            "Recherche",
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.text,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
        )
        SearchField(memory.text, { memory.text = it }, Modifier.focusRequester(field)) {
            memory.immediate = true
            memory.flush++
            keyboard?.hide()
        }
        when (val s = memory.state) {
            SearchState.Idle -> Centered("Saisissez au moins deux lettres d'un titre de film, de série ou d'épisode.")
            SearchState.Loading -> Centered("Recherche…")
            is SearchState.Failed -> Retry("Impossible de lancer la recherche : ${s.message}") { memory.attempt++ }
            is SearchState.Ready -> if (s.results.isEmpty) {
                Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucun film, série ou épisode pour « ${s.results.query} ».\n" +
                            "La recherche porte sur le début des mots du titre : « heure » trouve « 24 Heures chrono », « eures » ne le trouve pas.",
                        color = Palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Results(searchGroups(s.results), memory, client, images) { group, item ->
                    memory.opened = group.title to item.id
                    when (item.type) {
                        "Movie" -> onOpenMovie(item.id)
                        "Series" -> onOpenSeries(item.id)
                        // No episode page yet: its series, where it can be played
                        "Episode" -> item.seriesId?.let(onOpenSeries)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier, onSearch: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.padding(horizontal = 48.dp, vertical = 8.dp).fillMaxWidth(0.6f)
            .background(Palette.surface, shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) Palette.accent else Palette.surfaceRaised, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text("Titre d'un film, d'une série ou d'un épisode", color = Palette.textSecondary, fontSize = 20.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = Palette.text, fontSize = 20.sp),
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            // A text field keeps the D-pad for its cursor: Down goes on to the results
            modifier = modifier.fillMaxWidth().onPreviewKeyEvent {
                if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                    focusManager.moveFocus(FocusDirection.Down)
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun Results(
    groups: List<SearchGroup>,
    memory: SearchMemory,
    client: EmbyClient,
    images: ImageLoader,
    onOpen: (SearchGroup, BaseItemDto) -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Back from a page: the card that opened it, one frame first so it is placed
    val target = memory.opened?.takeIf { (title, id) -> groups.any { it.title == title && it.items.any { i -> i.id == id } } }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
    LazyColumn(state = memory.column, contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(groups, key = { it.title }) { group ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${group.title} · ${group.items.size}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.text,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
                LazyRow(
                    state = memory.rows.getOrPut(group.title) { LazyListState() },
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(group.items, key = { it.id }) { item ->
                        val modifier = if (target == group.title to item.id) Modifier.focusRequester(focus) else Modifier
                        ItemCard(item, group.landscape, client, images, modifier) { onOpen(group, item) }
                    }
                }
                group.note?.let { Text(it, color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 48.dp)) }
            }
        }
    }
}
