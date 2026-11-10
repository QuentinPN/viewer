package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.playlistEntries
import io.github.quentinpn.viewer.tv.emby.videoPlaylists

// The « Listes de lecture » row of the home and the page of one playlist, read only (ADR 0088).

/** Title of the home row, also its key in `HomeMemory`. */
internal const val PLAYLISTS_SHELF = "Listes de lecture"

/** Grid items before the first entry: the playlist's header. */
private const val HEADER_ITEMS = 1

/** The home row of the user's video playlists; null when there is none, so the row hides. */
internal fun playlistShelf(items: List<BaseItemDto>): Shelf? =
    videoPlaylists(items).takeIf { it.isNotEmpty() }?.let { Shelf(PLAYLISTS_SHELF, it, landscape = false, resume = false) }

/** "1 titre", "12 titres". */
internal fun titlesLabel(count: Int): String = if (count == 1) "1 titre" else "$count titres"

/**
 * "3 titres · 4 h 12": the count of [entries], with their total length only when every one
 * has a length, since a partial sum would read as the whole.
 */
internal fun playlistSummary(entries: List<BaseItemDto>): String {
    val count = titlesLabel(entries.size)
    val lengths = entries.map { it.runTimeTicks?.takeIf { t -> t > 0 } }
    if (entries.isEmpty() || lengths.any { it == null }) return count
    return "$count · ${length(lengths.sumOf { it ?: 0 })}"
}

/** The page an entry opens: a film's page, an episode's page over this playlist; null for anything else. */
internal fun playlistEntryPage(item: BaseItemDto): DetailPage? = when (item.type) {
    "Movie" -> DetailPage.Movie(item.id)
    "Episode" -> episodeTarget(item)?.let { DetailPage.Episode(it) }
    else -> null
}

/**
 * A playlist (ADR 0088): its name, count and length, then its films and episodes in the
 * playlist's order as the « Reprendre » cards, an episode under its series with « S · É ».
 * OK on an entry opens its page; Back from it focuses that entry again ([page] `opened`).
 * No « Lire »: the player plays one item, with no queue to go on with the next entry.
 */
@Composable
internal fun PlaylistScreen(
    client: EmbyClient,
    userId: String,
    page: DetailPage.Playlist,
    images: ImageLoader,
    onOpen: (BaseItemDto) -> Unit,
    onSessionExpired: () -> Unit,
) {
    var entries by remember { mutableStateOf<Load<List<BaseItemDto>>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        entries = Load.Loading
        entries = try {
            Load.Ready(playlistEntries(client.playlistItems(userId, page.id).items))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            Load.Failed(e.error.message)
        }
    }

    val grid = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Palette.background)) {
        val loaded = (entries as? Load.Ready)?.value
        // The entry opened last, else the first one
        val target = loaded?.let { list -> page.opened?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            state = grid,
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(0.7f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(page.name, style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    loaded?.takeIf { it.isNotEmpty() }?.let {
                        Text(playlistSummary(it), color = Palette.textSecondary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            when (val e = entries) {
                Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Text("Chargement…", color = Palette.textSecondary) }
                is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Retry(e.message) { attempt++ } }
                is Load.Ready -> if (e.value.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Aucun film ni épisode dans cette liste de lecture.", color = Palette.textSecondary)
                    }
                } else {
                    items(e.value, key = { it.id }) { item ->
                        val modifier = if (item.id == target) Modifier.focusRequester(focus) else Modifier
                        ItemCard(item, landscape = true, client, images, modifier, fill = true) { onOpen(item) }
                    }
                }
            }
        }
        LaunchedEffect(target) {
            val list = loaded ?: return@LaunchedEffect
            val index = list.indexOfFirst { it.id == target }
            if (index < 0) return@LaunchedEffect
            // An item off screen cannot take focus: brought in first, then focused once placed
            if (index > 0) grid.scrollToItem(index + HEADER_ITEMS)
            withFrameNanos {}
            runCatching { focus.requestFocus() }
            // An entry in the first row: focusing it scrolls the header off, so the page goes back to the top
            val placed = grid.layoutInfo.visibleItemsInfo
            val firstRow = placed.firstOrNull { it.index == HEADER_ITEMS }?.row
            if (firstRow != null && placed.firstOrNull { it.index == index + HEADER_ITEMS }?.row == firstRow) {
                withFrameNanos {}
                grid.scrollToItem(0)
            }
        }
    }
}
