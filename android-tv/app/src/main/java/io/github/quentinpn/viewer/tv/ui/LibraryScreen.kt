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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
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
import io.github.quentinpn.viewer.tv.emby.LetterStart
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter
import io.github.quentinpn.viewer.tv.emby.pageEnd
import io.github.quentinpn.viewer.tv.emby.pageLimit
import kotlinx.coroutines.launch

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

    /** Id of the card last opened, or reached by a letter jump: it takes the focus. */
    internal var opened by mutableStateOf<String?>(null)

    /** False after a toolbar change, so focus stays on the toolbar while the grid reloads. */
    internal var focusGrid = true

    /** Genres of the library, read once when the genre list first opens. */
    internal var genres by mutableStateOf<List<String>?>(null)

    /** Decades of the library, newest first, read once when the decade list first opens (ADR 0082). */
    internal var decades by mutableStateOf<List<Int>?>(null)

    /** Items to read before paging waits for the scroll again: the end of a jump's page (ADR 0070). */
    internal var wanted by mutableIntStateOf(0)

    /** A letter picked whose first title is not focused yet. */
    internal var jump by mutableStateOf<Jump?>(null)

    /** First title of each letter for the current query, read when the letter picker first opens. */
    internal var letters by mutableStateOf<Map<Char, LetterStart>?>(null)

    /** Letter picked last: the picker opens on it. */
    internal var letter: Char? = null

    /** True after « Au hasard » opened a title: Back gives the focus to that pill (ADR 0077). */
    internal var focusRandom = false

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
        wanted = 0
        jump = null
        letters = null
        letter = null
        focusRandom = false
    }
}

/** A letter jump; compared by identity, so picking the same letter twice jumps twice. */
internal class Jump(val start: LetterStart)

/**
 * True when the grid is near the end of the loaded items and the server has more, or when
 * a letter jump still [wanted] the page holding its first title.
 */
internal fun shouldLoadMore(lastVisible: Int, loaded: Int, total: Int, margin: Int = PAGE_MARGIN, wanted: Int = 0): Boolean =
    total < 0 || (loaded < total && (lastVisible >= loaded - margin || loaded < wanted))

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
    var pickingLetter by remember { mutableStateOf(false) }
    var pickingDecade by remember { mutableStateOf(false) }
    val genreButton = remember { FocusRequester() }
    val decadeButton = remember { FocusRequester() }
    val letterButton = remember { FocusRequester() }
    val randomButton = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // A random pick on its way: more presses are ignored (ADR 0077)
    var drawing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun loadPage() {
        val generation = memory.generation
        try {
            val library = memory.library
            // After a jump, every page up to the letter's in one request: the grid stays one list
            val limit = pageLimit(memory.fetched, memory.wanted, PAGE_SIZE)
            val page = client.libraryItems(userId, library.id, library.itemTypes, memory.fetched, limit, memory.options)
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
        notice = null
        memory.change(next)
        onOptionsChanged(next)
    }

    // One title drawn by the server with the grid's filters, opened as its poster would be (ADR 0077)
    fun pickRandom() {
        if (drawing) return
        drawing = true
        notice = null
        val generation = memory.generation
        val library = memory.library
        scope.launch {
            try {
                val item = client.randomLibraryItem(userId, library.id, library.itemTypes, memory.options)
                // The filters changed while the pick travelled: it belongs to the old ones
                if (generation != memory.generation) return@launch
                if (item == null) {
                    notice = if (memory.options.narrowed) "Aucun titre avec ces filtres." else "Cette bibliothèque est vide."
                    return@launch
                }
                memory.focusGrid = false
                memory.focusRandom = true
                if (item.type == "Series") onOpenSeries(item.id) else onOpenMovie(item.id)
            } catch (e: EmbyException) {
                if (e.error == EmbyError.SessionExpired) onSessionExpired()
                notice = e.error.message
            } finally {
                drawing = false
            }
        }
    }

    // Back from a title drawn at random: the focus returns to « Au hasard », once the toolbar is placed
    LaunchedEffect(Unit) {
        if (!memory.focusRandom) return@LaunchedEffect
        memory.focusRandom = false
        withFrameNanos {}
        runCatching { randomButton.requestFocus() }
    }

    // Pages follow the scroll; read again when a page lands, in case it did not fill the screen.
    // A new query restarts the collector, which cancels a page still on its way.
    LaunchedEffect(attempt, memory.generation) {
        snapshotFlow { Triple(memory.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, memory.items.size, memory.wanted) }
            .collect { (last, _, wanted) ->
                if (memory.error == null && shouldLoadMore(last, memory.fetched, memory.total, wanted = wanted)) loadPage()
            }
    }

    // The page of the letter is read by the collector above, the only one that loads
    fun jumpTo(start: LetterStart) {
        memory.wanted = maxOf(memory.wanted, pageEnd(start.position, PAGE_SIZE))
        memory.focusGrid = true
        memory.jump = Jump(start)
    }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // A studio's grid is named after it (ADR 0087)
                Text(gridTitle(memory.library, memory.options), style = MaterialTheme.typography.headlineMedium, color = Palette.text)
                if (memory.total >= 0) {
                    val count = memory.total
                    Text(if (count > 1) "$count titres" else "$count titre", color = Palette.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                }
                notice?.let { Text(it, color = Palette.textSecondary, modifier = Modifier.padding(bottom = 4.dp)) }
            }
            Toolbar(
                memory.options,
                memory.library.itemTypes,
                ToolbarFocus(genreButton, decadeButton, letterButton, randomButton),
                ::change,
                onPickLetter = { pickingLetter = true },
                onPickRandom = ::pickRandom,
                onPickDecade = { pickingDecade = true },
            ) { picking = true }
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
            SidePicker(
                loaded = memory.genres,
                load = { memory.genres = client.genres(userId, memory.library.id, memory.library.itemTypes) },
                current = memory.options.genre,
                label = { it ?: "Tous les genres" },
                modifier = Modifier.align(Alignment.CenterEnd),
                onSessionExpired = onSessionExpired,
            ) { genre ->
                picking = false
                if (genre != null) change(memory.options.copy(genre = genre.value))
                // Back to the genre button once the list closes
                runCatching { genreButton.requestFocus() }
            }
        }
        if (pickingDecade) {
            SidePicker(
                loaded = memory.decades,
                load = { memory.decades = client.decades(userId, memory.library.id, memory.library.itemTypes) },
                current = memory.options.decade,
                label = ::decadeChoiceLabel,
                modifier = Modifier.align(Alignment.CenterEnd),
                onSessionExpired = onSessionExpired,
            ) { decade ->
                pickingDecade = false
                if (decade != null) change(memory.options.copy(decade = decade.value))
                // Back to the « Années » pill once the list closes
                runCatching { decadeButton.requestFocus() }
            }
        }
        if (pickingLetter) {
            LetterPicker(client, userId, memory, Modifier.align(Alignment.CenterEnd), onSessionExpired) { start ->
                pickingLetter = false
                // A letter: the grid takes the focus on its first title; Back: the letter button
                if (start != null) jumpTo(start) else runCatching { letterButton.requestFocus() }
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

/** Pills that take the focus back when a side list or a page they opened closes. */
private class ToolbarFocus(val genre: FocusRequester, val decade: FocusRequester, val letter: FocusRequester, val random: FocusRequester)

/** Glass pills in the home's style (ADR 0067); each steps through its choices, white when it narrows the grid. */
@Composable
private fun Toolbar(
    options: ItemsOptions,
    itemTypes: String,
    focus: ToolbarFocus,
    onChange: (ItemsOptions) -> Unit,
    onPickLetter: () -> Unit,
    onPickRandom: () -> Unit,
    onPickDecade: () -> Unit,
    onPickGenre: () -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButton(onClick = { onChange(options.nextSort()) }) { PillText("Tri : ${sortLabel(options.sort)}") }
        GlassButton(onClick = { onChange(options.copy(descending = !options.descending)) }) { PillText(directionLabel(options)) }
        // Jumping by letter only follows a sort by title (ADR 0070)
        if (options.sort == SortKey.Name) {
            GlassButton(
                onClick = onPickLetter,
                modifier = Modifier.focusRequester(focus.letter).semantics { contentDescription = "Aller à une lettre" },
            ) { PillText("Lettre") }
        }
        Toggle(options.watch != WatchFilter.All, "Visionnage : ${watchLabel(options.watch)}") { onChange(options.nextWatch(itemTypes)) }
        Toggle(options.favorites, "Favoris") { onChange(options.copy(favorites = !options.favorites)) }
        Toggle(options.genre != null, "Genre : ${options.genre ?: "Tous"}", Modifier.focusRequester(focus.genre), onPickGenre)
        // Filled while a decade narrows the grid (ADR 0082)
        Toggle(options.decade != null, decadePillLabel(options.decade), Modifier.focusRequester(focus.decade), onPickDecade)
        GlassButton(onClick = { onChange(resetOptions(options)) }) { PillText("Réinitialiser") }
        // Opens one title drawn among those the filters keep (ADR 0077)
        GlassButton(
            onClick = onPickRandom,
            modifier = Modifier.focusRequester(focus.random).semantics { contentDescription = "Ouvrir un titre au hasard" },
        ) { PillText("Au hasard") }
    }
}

/** One pill whether on or off, so the focus stays on it when it changes. */
@Composable
private fun Toggle(on: Boolean, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = modifier.semantics { selected = on }, selected = on) { PillText(label) }
}

@Composable
internal fun PillText(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A picked genre or decade; [value] null means all of them. */
private class Picked<T>(val value: T?)

/**
 * Side list of the library's genres or decades (ADR 0045, 0082): « all » first, then [loaded],
 * read by [load] while still null. Back closes it without a change.
 */
@Composable
private fun <T : Any> SidePicker(
    loaded: List<T>?,
    load: suspend () -> Unit,
    current: T?,
    label: (T?) -> String,
    modifier: Modifier,
    onSessionExpired: () -> Unit,
    onDone: (Picked<T>?) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    BackHandler { onDone(null) }
    LaunchedEffect(attempt) {
        if (loaded != null) return@LaunchedEffect
        try {
            load()
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            error = e.error.message
        }
    }
    val panel = modifier.fillMaxHeight().width(420.dp).background(Palette.surface)
    val failed = error
    when {
        loaded == null && failed != null -> Box(panel) {
            Retry(failed) {
                error = null
                attempt++
            }
        }
        loaded == null -> Box(panel, contentAlignment = Alignment.Center) { Text("Chargement…", color = Palette.textSecondary) }
        else -> {
            val choices = listOf<T?>(null) + loaded
            val first = remember { FocusRequester() }
            val start = choices.indexOf(current).coerceAtLeast(0)
            LazyColumn(
                panel,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(choices) { index, choice ->
                    val selected = choice == current
                    ListItem(
                        selected = selected,
                        onClick = { onDone(Picked(choice)) },
                        headlineContent = { Text(label(choice), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
    // A letter jump (ADR 0070): once its page is in, show its first title and focus it, with
    // no animation. Found by id; if the list moved since the index was read, by position.
    LaunchedEffect(memory.jump, memory.items.size, memory.total) {
        val jump = memory.jump ?: return@LaunchedEffect
        var index = memory.items.indexOfFirst { it.id == jump.start.id }
        if (index < 0) {
            val waiting = memory.fetched < memory.wanted && memory.fetched < memory.total && memory.error == null
            if (waiting) return@LaunchedEffect
            index = jump.start.position.coerceAtMost(memory.items.lastIndex)
        }
        memory.opened = memory.items[index].id
        memory.grid.scrollToItem(index)
        // The card takes the focus requester on the next composition
        repeat(2) {
            withFrameNanos {}
            runCatching { focus.requestFocus() }
        }
        // Last: clearing the key restarts this effect, which would cancel the steps above
        memory.jump = null
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
