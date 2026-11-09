package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException

private const val PAGE_SIZE = 60

/** The next page loads once the last card shown is this close to the end of what is loaded. */
private const val PAGE_MARGIN = 18

/** A film or series library from `/Views`; [itemTypes] is `Movie` or `Series`. */
data class Library(val id: String, val name: String, val itemTypes: String)

/**
 * Grid state that outlives the screen while a film or series page is shown, so
 * coming back keeps the loaded pages, the scroll and the focused card.
 */
class LibraryMemory(val library: Library) {
    internal val items = mutableStateListOf<BaseItemDto>()

    /** Items read from the server, duplicates included: the next page starts there. */
    internal var fetched = 0

    /** -1 until the first page answers. */
    internal var total by mutableIntStateOf(-1)
    internal var error by mutableStateOf<String?>(null)
    internal val grid = LazyGridState()

    /** Id of the card last opened. */
    internal var opened: String? = null
}

/** True when the grid is near the end of the loaded items and the server has more. */
internal fun shouldLoadMore(lastVisible: Int, loaded: Int, total: Int, margin: Int = PAGE_MARGIN): Boolean =
    total < 0 || (loaded < total && lastVisible >= loaded - margin)

/** Every film or series of a library, by title, in a poster grid paged as it scrolls. */
@Composable
fun LibraryScreen(
    client: EmbyClient,
    userId: String,
    images: ImageLoader,
    memory: LibraryMemory,
    onOpenSeries: (seriesId: String) -> Unit,
    onOpenMovie: (movieId: String) -> Unit,
    onSessionExpired: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }

    suspend fun loadPage() {
        try {
            val page = client.libraryItems(userId, memory.library.id, memory.library.itemTypes, memory.fetched, PAGE_SIZE)
            memory.fetched += page.items.size
            // Titles that tie can come back on two pages; one card each
            val known = memory.items.mapTo(HashSet()) { it.id }
            memory.items += page.items.filter { known.add(it.id) }
            // An empty page ends the paging even if the count said more
            memory.total = if (page.items.isEmpty()) memory.items.size else page.totalRecordCount
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            memory.error = e.error.message
        }
    }

    // Pages follow the scroll; read again when a page lands, in case it did not fill the screen
    LaunchedEffect(attempt) {
        snapshotFlow { (memory.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to memory.items.size }
            .collect { (last, _) ->
                if (memory.error == null && shouldLoadMore(last, memory.fetched, memory.total)) loadPage()
            }
    }

    Column(Modifier.fillMaxSize().background(Palette.background)) {
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
        val error = memory.error
        when {
            memory.items.isEmpty() && error != null -> Retry(error) {
                memory.error = null
                attempt++
            }
            memory.items.isEmpty() && memory.total == 0 -> Centered("Cette bibliothèque est vide.")
            memory.items.isEmpty() -> Centered("Chargement…")
            else -> Grid(memory, client, images, error, onRetry = {
                memory.error = null
                attempt++
            }) { item ->
                memory.opened = item.id
                if (item.type == "Series") onOpenSeries(item.id) else onOpenMovie(item.id)
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
    // The card opened last, else the first one; one frame first so the card is placed
    val target = memory.opened?.takeIf { id -> memory.items.any { it.id == id } } ?: memory.items.first().id
    LaunchedEffect(Unit) {
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
