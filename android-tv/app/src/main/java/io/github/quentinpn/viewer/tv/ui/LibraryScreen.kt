package io.github.quentinpn.viewer.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.onFocusChanged
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
import io.github.quentinpn.viewer.tv.emby.UserItemData
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

    /** False after a change from the icon rail, so focus stays on the rail while the grid reloads. */
    internal var focusGrid = true

    /** Genres of the library, read once when the genre list first opens. */
    internal var genres by mutableStateOf<List<String>?>(null)

    /** Decades of the library, newest first, read once when the decade list first opens. */
    internal var decades by mutableStateOf<List<Int>?>(null)

    /** Items to read before paging waits for the scroll again: the end of a jump's page. */
    internal var wanted by mutableIntStateOf(0)

    /** A letter picked whose first title is not focused yet. */
    internal var jump by mutableStateOf<Jump?>(null)

    /** First title of each letter for the current query, read when the letter rail first shows it. */
    internal var letters by mutableStateOf<Map<Char, LetterStart>?>(null)

    /** Letter picked last: drawn in the accent on the rail. */
    internal var letter by mutableStateOf<Char?>(null)

    /** True after « Aléatoire » opened a title: Back gives the focus to that icon. */
    internal var focusRandom = false

    /**
     * A card menu's write answered with [data] for [itemId] (« Grille bibliothèque »):
     * the card changes in place, or leaves when the filters no longer hold it, with the count,
     * the next page's start and the letter index moved up by one. The card, or the one that took
     * its place, takes the focus.
     */
    internal fun userDataChanged(itemId: String, data: UserItemData) {
        when (val change = gridChange(items, itemId, data, options)) {
            is GridChange.Updated -> {
                items[change.index] = change.item
                opened = itemId
            }
            is GridChange.Removed -> {
                items.removeAt(change.index)
                // The server's list lost a title before the next page's start
                fetched = (fetched - 1).coerceAtLeast(0)
                if (total > 0) total--
                letters = letters?.let { lettersAfterRemoval(it, change.index, itemId, items.getOrNull(change.index)?.id, total) }
                opened = change.focus
            }
            GridChange.Absent -> Unit
        }
    }

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

/** Distance from the screen's sides to the rails, inside the TV's safe area at 960 x 540 dp. */
private val EDGE = 32.dp

/** Width of the letter rail, and of the icon rail's round buttons. */
private val LETTER_RAIL = 24.dp
private val ICON_RAIL = 40.dp

/** Space kept right of the grid for the icon rail. */
private val ICON_RAIL_SPACE = EDGE + ICON_RAIL + 12.dp

/** A side panel opened from the icon rail. */
private enum class Panel { Sort, Filters, Genre, Decade }

/**
 * Every film or series of a library in a poster grid paged as it scrolls, between a
 * rail of letters on the left and a rail of icons for sort and filters on the right.
 */
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
    /** A card menu's play rows, as the home's: the player negotiates the stream. */
    onPlay: (itemId: String, startTicks: Long) -> Unit,
    /** After a write or a playlist change from a card's menu: the home reads its rows again. */
    onUserDataChanged: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    val rail = remember { RailAction.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    // A random pick on its way: more presses are ignored
    var drawing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Position of the focused card, for the counter; -1 before one has the focus
    var focused by remember { mutableIntStateOf(-1) }

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
        focused = -1
        memory.change(next)
        onOptionsChanged(next)
    }

    // Once the rail is placed again: a panel closed, or the reset icon went away under the focus
    fun focusRail(action: RailAction) {
        scope.launch {
            withFrameNanos {}
            runCatching { rail.getValue(action).requestFocus() }
        }
    }

    // One title drawn by the server with the grid's filters, opened as its poster would be
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

    fun onRail(action: RailAction) {
        val options = memory.options
        when (action) {
            RailAction.Sort -> panel = Panel.Sort
            RailAction.Watch -> change(options.nextWatch(memory.library.itemTypes))
            RailAction.Favorites -> change(options.copy(favorites = !options.favorites))
            RailAction.Filters -> panel = Panel.Filters
            RailAction.Random -> pickRandom()
            RailAction.Reset -> {
                change(resetOptions(options))
                // The reset icon leaves the rail: the focus goes to the first icon
                focusRail(RailAction.Sort)
            }
        }
    }

    // Back from a title drawn at random: the focus returns to the « Aléatoire » icon, once the rail is placed
    LaunchedEffect(Unit) {
        if (!memory.focusRandom) return@LaunchedEffect
        memory.focusRandom = false
        withFrameNanos {}
        runCatching { rail.getValue(RailAction.Random).requestFocus() }
    }

    // Pages follow the scroll; read again when a page lands, in case it did not fill the screen.
    // A new query restarts the collector, which cancels a page still on its way.
    LaunchedEffect(attempt, memory.generation) {
        snapshotFlow { Triple(memory.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, memory.items.size, memory.wanted) }
            .collect { (last, _, wanted) ->
                if (memory.error == null && shouldLoadMore(last, memory.fetched, memory.total, wanted = wanted)) loadPage()
            }
    }

    // A card's menu, as on the home but without « Retirer de Reprendre ». A write
    // changes its card in place or takes it off the grid; a failure goes back to the menu,
    // which shows it and handles an expired session.
    val cardMenu: @Composable (BaseItemDto, () -> Unit) -> Unit = { item, close ->
        CardMenu(
            item,
            menuActions(item),
            client,
            userId,
            images,
            onDismiss = close,
            onPlay = { id, startTicks ->
                notice = null
                memory.opened = item.id
                memory.focusGrid = true
                onPlay(id, startTicks)
            },
            onSessionExpired = onSessionExpired,
            onPlaylistChanged = onUserDataChanged,
        ) { action ->
            val generation = memory.generation
            val data = client.perform(userId, item.id, action)
            // Never with the menu open, but a page of an older query is not edited
            if (generation == memory.generation) {
                memory.userDataChanged(item.id, data)
                // The last card left: the grid goes, the focus goes to the rail
                if (memory.items.isEmpty()) focusRail(RailAction.Sort)
            }
            onUserDataChanged()
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
                Modifier.padding(start = EDGE, end = ICON_RAIL_SPACE, top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A studio's grid is named after it
                Text(gridTitle(memory.library, memory.options), style = MaterialTheme.typography.titleLarge, color = Palette.text, maxLines = 1)
                if (memory.total >= 0) Text(titlesLabel(memory.total), color = Palette.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
                notice?.let { Text(it, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp)) }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.padding(start = EDGE, top = 12.dp, bottom = 16.dp).width(LETTER_RAIL).fillMaxHeight()) {
                    // Jumping by letter only follows a sort by title
                    if (memory.options.sort == SortKey.Name) {
                        LetterRail(client, userId, memory, Modifier.fillMaxSize(), onSessionExpired, ::jumpTo)
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
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
                        else -> Grid(memory, client, images, error, cardMenu, onFocused = { focused = it }, onRetry = {
                            memory.error = null
                            attempt++
                        }) { item ->
                            memory.opened = item.id
                            memory.focusGrid = true
                            if (item.type == "Series") onOpenSeries(item.id) else onOpenMovie(item.id)
                        }
                    }
                }
                Spacer(Modifier.width(ICON_RAIL_SPACE))
            }
        }
        IconRail(memory.options, rail, Modifier.align(Alignment.CenterEnd).padding(end = EDGE), ::onRail)
        positionLabel(focused, memory.total)?.let {
            Text(it, color = Palette.textSecondary, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = EDGE, bottom = 14.dp))
        }
        when (panel) {
            Panel.Sort -> ChoicePanel(
                listOf("Trier par" to sortKeyChoices(memory.options), "Ordre" to directionChoices(memory.options)),
                Modifier.align(Alignment.CenterEnd),
            ) { choice ->
                panel = null
                if (choice != null) change(choice.options)
                focusRail(RailAction.Sort)
            }
            Panel.Filters -> {
                val options = memory.options
                // One entry for both lists: each opens the side list it had as a pill
                val genre = PanelChoice("Genre : ${options.genre ?: "Tous"}", options, options.genre != null)
                val decade = PanelChoice(decadePillLabel(options.decade), options, options.decade != null)
                ChoicePanel(listOf("Filtres" to listOf(genre, decade)), Modifier.align(Alignment.CenterEnd)) { choice ->
                    panel = when {
                        choice === genre -> Panel.Genre
                        choice === decade -> Panel.Decade
                        else -> null
                    }
                    if (panel == null) focusRail(RailAction.Filters)
                }
            }
            Panel.Genre -> SidePicker(
                loaded = memory.genres,
                load = { memory.genres = client.genres(userId, memory.library.id, memory.library.itemTypes) },
                current = memory.options.genre,
                label = { it ?: "Tous les genres" },
                modifier = Modifier.align(Alignment.CenterEnd),
                onSessionExpired = onSessionExpired,
            ) { genre ->
                panel = null
                if (genre != null) change(memory.options.copy(genre = genre.value))
                // Back to the « Filtres » icon once the list closes
                focusRail(RailAction.Filters)
            }
            Panel.Decade -> SidePicker(
                loaded = memory.decades,
                load = { memory.decades = client.decades(userId, memory.library.id, memory.library.itemTypes) },
                current = memory.options.decade,
                label = ::decadeChoiceLabel,
                modifier = Modifier.align(Alignment.CenterEnd),
                onSessionExpired = onSessionExpired,
            ) { decade ->
                panel = null
                if (decade != null) change(memory.options.copy(decade = decade.value))
                focusRail(RailAction.Filters)
            }
            null -> Unit
        }
    }
}

@Composable
internal fun PillText(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}


/** A picked genre or decade; [value] null means all of them. */
private class Picked<T>(val value: T?)

/**
 * Side list of the library's genres or decades: « all » first, then [loaded],
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

/** Posters per row: with both rails, six 2:3 cards about 120 dp wide fit 960 dp. */
private const val GRID_COLUMNS = 6

@Composable
private fun Grid(
    memory: LibraryMemory,
    client: EmbyClient,
    images: ImageLoader,
    error: String?,
    /** A card's menu, shown over the card; its last argument closes it. */
    cardMenu: @Composable (BaseItemDto, () -> Unit) -> Unit,
    onFocused: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpen: (BaseItemDto) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Id of the card whose menu is open
    var menu by remember { mutableStateOf<String?>(null) }
    // The card opened last, else the first one; one frame first so the card is placed.
    // After a change from the rail the focus stays on the rail.
    val target = memory.opened?.takeIf { id -> memory.items.any { it.id == id } } ?: memory.items.first().id
    LaunchedEffect(Unit) {
        if (!memory.focusGrid) return@LaunchedEffect
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
    // A letter jump: once its page is in, show its first title and focus it, with
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
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = memory.grid,
        // Room around the cards for the focused one's scale and ring
        contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItemsIndexed(memory.items, key = { _, item -> item.id }) { index, item ->
            val modifier = (if (item.id == target) Modifier.focusRequester(focus) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused(index) }
            val hasMenu = menuActions(item).isNotEmpty()
            // The menu's popup is anchored on this box, the card's bounds
            Box {
                LibraryCard(
                    item,
                    client,
                    images,
                    modifier,
                    // A long press on OK opens the card's menu; the card keeps the focus target
                    onLongClick = if (hasMenu) ({
                        memory.opened = item.id
                        menu = item.id
                    }) else null,
                ) { onOpen(item) }
                if (menu == item.id) {
                    cardMenu(item) {
                        menu = null
                        // memory.opened is this card, or the one that took its place once it left
                        // the grid: its focus requester is placed on the next composition
                        scope.launch {
                            repeat(2) {
                                withFrameNanos {}
                                runCatching { focus.requestFocus() }
                            }
                        }
                    }
                }
            }
        }
        // A later page that failed: the loaded cards stay, with a way to try again
        if (error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { Retry(error, onRetry) }
        }
    }
}
