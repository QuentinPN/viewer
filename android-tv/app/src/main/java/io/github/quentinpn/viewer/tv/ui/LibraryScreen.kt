package io.github.quentinpn.viewer.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter

private const val PAGE_SIZE = 60

/** The next page loads once the last card shown is this close to the end of what is loaded. */
private const val PAGE_MARGIN = 18

/** A film or series library from `/Views`; [itemTypes] is `Movie` or `Series`. */
data class Library(val id: String, val name: String, val itemTypes: String)

/**
 * Grid state that outlives the screen while a film or series page is shown, so
 * coming back keeps the loaded pages, the scroll and the focused card.
 */
class LibraryMemory(val library: Library, options: ItemsOptions = ItemsOptions()) {
    internal var options by mutableStateOf(options)
        private set

    /** Bumped on every sort or filter change: pages of an older query are dropped. */
    internal var generation by mutableIntStateOf(0)
        private set
    internal val items = mutableStateListOf<BaseItemDto>()

    /** Items read from the server, duplicates included: the next page starts there. */
    internal var fetched = 0

    /** -1 until the first page answers. */
    internal var total by mutableIntStateOf(-1)
    internal var error by mutableStateOf<String?>(null)
    /** Replaced on every change, so a new query starts at the top. */
    internal var grid by mutableStateOf(LazyGridState())
        private set

    /** Id of the card last opened. */
    internal var opened: String? = null

    /** False after a toolbar change, so focus stays on the toolbar while the grid reloads. */
    internal var focusGrid = true

    /** Genres of the library, read once when the genre list first opens. */
    internal var genres by mutableStateOf<List<String>?>(null)

    /** New sort or filters: back to the first page. */
    internal fun change(next: ItemsOptions) {
        if (next == options) return
        options = next
        generation++
        items.clear()
        fetched = 0
        total = -1
        error = null
        opened = null
        grid = LazyGridState()
        focusGrid = false
    }
}

/** True when the grid is near the end of the loaded items and the server has more. */
internal fun shouldLoadMore(lastVisible: Int, loaded: Int, total: Int, margin: Int = PAGE_MARGIN): Boolean =
    total < 0 || (loaded < total && lastVisible >= loaded - margin)

/** Every film or series of a library in a poster grid paged as it scrolls, with sort and filters. */
@Composable
fun LibraryScreen(
    client: EmbyClient,
    userId: String,
    images: ImageLoader,
    memory: LibraryMemory,
    onOptionsChanged: (ItemsOptions) -> Unit,
    onOpenSeries: (seriesId: String) -> Unit,
    onOpenMovie: (movieId: String) -> Unit,
    onSessionExpired: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    val genreButton = remember { FocusRequester() }

    suspend fun loadPage() {
        val generation = memory.generation
        try {
            val library = memory.library
            val page = client.libraryItems(userId, library.id, library.itemTypes, memory.fetched, PAGE_SIZE, memory.options)
            // A sort or filter change while this page travelled: it belongs to the old query
            if (generation != memory.generation) return
            memory.fetched += page.items.size
            // Titles that tie can come back on two pages; one card each
            val known = memory.items.mapTo(HashSet()) { it.id }
            memory.items += page.items.filter { known.add(it.id) }
            // An empty page ends the paging even if the count said more
            memory.total = if (page.items.isEmpty()) memory.items.size else page.totalRecordCount
        } catch (e: EmbyException) {
            if (generation != memory.generation) return
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            memory.error = e.error.message
        }
    }

    fun change(next: ItemsOptions) {
        if (next == memory.options) return
        memory.change(next)
        onOptionsChanged(next)
    }

    // Pages follow the scroll; read again when a page lands, in case it did not fill the screen.
    // A new query restarts the collector, which cancels a page still on its way.
    LaunchedEffect(attempt, memory.generation) {
        snapshotFlow { (memory.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to memory.items.size }
            .collect { (last, _) ->
                if (memory.error == null && shouldLoadMore(last, memory.fetched, memory.total)) loadPage()
            }
    }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(memory.library.name, style = MaterialTheme.typography.headlineMedium, color = Palette.text)
                if (memory.total >= 0) {
                    val count = memory.total
                    Text(if (count > 1) "$count titres" else "$count titre", color = Palette.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            Toolbar(memory.options, memory.library.itemTypes, genreButton, ::change) { picking = true }
            val error = memory.error
            when {
                memory.items.isEmpty() && error != null -> Retry(error) {
                    memory.error = null
                    attempt++
                }
                memory.items.isEmpty() && memory.total == 0 && memory.options.narrowed ->
                    Centered("Aucun titre ne correspond à ces filtres.")
                memory.items.isEmpty() && memory.total == 0 -> Centered("Cette bibliothèque est vide.")
                memory.items.isEmpty() -> Centered("Chargement…")
                else -> Grid(memory, client, images, error, onRetry = {
                    memory.error = null
                    attempt++
                }) { item ->
                    memory.opened = item.id
                    memory.focusGrid = true
                    if (item.type == "Series") onOpenSeries(item.id) else onOpenMovie(item.id)
                }
            }
        }
        if (picking) {
            GenrePicker(client, userId, memory, Modifier.align(Alignment.CenterEnd), onSessionExpired) { genre ->
                picking = false
                if (genre != null) change(memory.options.copy(genre = genre.value))
                // Back to the genre button once the list closes
                runCatching { genreButton.requestFocus() }
            }
        }
    }
}

private fun sortLabel(key: SortKey) = when (key) {
    SortKey.Name -> "Titre"
    SortKey.DateAdded -> "Date d'ajout"
    SortKey.Year -> "Année"
    SortKey.Rating -> "Note"
}

private fun directionLabel(options: ItemsOptions) = when {
    options.sort == SortKey.Name -> if (options.descending) "Z → A" else "A → Z"
    options.descending -> "Décroissant"
    else -> "Croissant"
}

private fun watchLabel(watch: WatchFilter) = when (watch) {
    WatchFilter.All -> "Tous"
    WatchFilter.Unplayed -> "Non vus"
    WatchFilter.Played -> "Vus"
    WatchFilter.InProgress -> "En cours"
}

/** Glass pills in the home's style (ADR 0067); each steps through its choices, white when it narrows the grid. */
@Composable
private fun Toolbar(
    options: ItemsOptions,
    itemTypes: String,
    genreButton: FocusRequester,
    onChange: (ItemsOptions) -> Unit,
    onPickGenre: () -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(onClick = { onChange(options.nextSort()) }) { PillText("Tri : ${sortLabel(options.sort)}") }
        GlassButton(onClick = { onChange(options.copy(descending = !options.descending)) }) { PillText(directionLabel(options)) }
        Toggle(options.watch != WatchFilter.All, "Visionnage : ${watchLabel(options.watch)}") { onChange(options.nextWatch(itemTypes)) }
        Toggle(options.favorites, "Favoris") { onChange(options.copy(favorites = !options.favorites)) }
        Toggle(options.genre != null, "Genre : ${options.genre ?: "Tous"}", Modifier.focusRequester(genreButton), onPickGenre)
        GlassButton(onClick = { onChange(ItemsOptions()) }) { PillText("Réinitialiser") }
    }
}

/** One pill whether on or off, so the focus stays on it when it changes. */
@Composable
private fun Toggle(on: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier.semantics { selected = on }, selected = on) { PillText(label) }
}

@Composable
private fun PillText(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A picked genre; [value] null means every genre. */
private class Picked(val value: String?)

/** Side list of the library's genres; Back closes it without a change. */
@Composable
private fun GenrePicker(
    client: EmbyClient,
    userId: String,
    memory: LibraryMemory,
    modifier: Modifier,
    onSessionExpired: () -> Unit,
    onDone: (Picked?) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    BackHandler { onDone(null) }
    LaunchedEffect(attempt) {
        if (memory.genres != null) return@LaunchedEffect
        try {
            memory.genres = client.genres(userId, memory.library.id, memory.library.itemTypes)
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            error = e.error.message
        }
    }
    val panel = modifier.fillMaxHeight().width(420.dp).background(Palette.surface)
    val genres = memory.genres
    val failed = error
    when {
        genres == null && failed != null -> Box(panel) {
            Retry(failed) {
                error = null
                attempt++
            }
        }
        genres == null -> Box(panel, contentAlignment = Alignment.Center) { Text("Chargement…", color = Palette.textSecondary) }
        else -> {
            val choices = listOf<String?>(null) + genres
            val first = remember { FocusRequester() }
            val start = choices.indexOf(memory.options.genre).coerceAtLeast(0)
            LazyColumn(
                panel,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(choices) { index, genre ->
                    val selected = genre == memory.options.genre
                    ListItem(
                        selected = selected,
                        onClick = { onDone(Picked(genre)) },
                        headlineContent = { Text(genre ?: "Tous les genres", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = if (selected) ({ Text("✓") }) else null,
                        modifier = if (index == start) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos {}
                runCatching { first.requestFocus() }
            }
        }
    }
}

@Composable
private fun Grid(
    memory: LibraryMemory,
    client: EmbyClient,
    images: ImageLoader,
    error: String?,
    onRetry: () -> Unit,
    onOpen: (BaseItemDto) -> Unit,
) {
    val focus = remember { FocusRequester() }
    // The card opened last, else the first one; one frame first so the card is placed.
    // After a toolbar change the focus stays on the toolbar.
    val target = memory.opened?.takeIf { id -> memory.items.any { it.id == id } } ?: memory.items.first().id
    LaunchedEffect(Unit) {
        if (!memory.focusGrid) return@LaunchedEffect
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = memory.grid,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(memory.items, key = { it.id }) { item ->
            val modifier = if (item.id == target) Modifier.focusRequester(focus) else Modifier
            ItemCard(item, landscape = false, client, images, modifier, fill = true) { onOpen(item) }
        }
        // A later page that failed: the loaded cards stay, with a way to try again
        if (error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { Retry(error, onRetry) }
        }
    }
}
