package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.quentinpn.viewer.tv.emby.CollectionRef
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.collectionRefs
import io.github.quentinpn.viewer.tv.emby.collectionTitles

// The « Collection : … » rows of a film page and the navigation from them.

/** One collection row: the BoxSet and its titles in release order, the page's own film among them. */
internal data class CollectionShelf(val ref: CollectionRef, val titles: List<BaseItemDto>)

/**
 * The shelf shown for [ref]: its films and series in release order, null when the page's film
 * [itemId] would be alone in it, a row with nothing else to open.
 */
internal fun collectionShelf(ref: CollectionRef, items: List<BaseItemDto>, itemId: String): CollectionShelf? {
    val titles = collectionTitles(items)
    return if (titles.any { it.id != itemId }) CollectionShelf(ref, titles) else null
}

/** The shelf whose poster [focus] takes the focus back: the first holding it; -1 for none. */
internal fun focusedShelf(shelves: List<CollectionShelf>, focus: String?): Int =
    if (focus == null) -1 else shelves.indexOfFirst { shelf -> shelf.titles.any { it.id == focus } }

/**
 * [page] over [pages], the film below remembering the poster [itemId] of its collection row,
 * so Back focuses it again; a person card remembered earlier is forgotten.
 */
internal fun openFromCollection(pages: List<DetailPage>, page: DetailPage, itemId: String): List<DetailPage> {
    val below = pages.lastOrNull()
    val kept = if (below is DetailPage.Movie) replaceTop(pages, below.copy(person = null, collection = itemId, genre = null, studio = null)) else pages
    return pushPage(kept, page)
}

/**
 * The collection rows of [movie], read once it is shown; null until all are read. A
 * collection that fails is left out, so its row hides and the page stays; an expired session
 * is still handled. Read once per film: a playback does not change its collections.
 */
@Composable
internal fun rememberCollections(client: EmbyClient, userId: String, movie: BaseItemDto?, onSessionExpired: () -> Unit): List<CollectionShelf>? {
    val refs = remember(movie?.id, movie?.rawCollections) { movie?.let { collectionRefs(it.rawCollections) } }
    var shelves by remember(movie?.id) { mutableStateOf<List<CollectionShelf>?>(null) }
    LaunchedEffect(refs) {
        if (refs == null || movie == null) return@LaunchedEffect
        shelves = refs.mapNotNull { ref ->
            try {
                collectionShelf(ref, client.collectionItems(userId, ref.id).items, movie.id)
            } catch (e: EmbyException) {
                if (e.error == EmbyError.SessionExpired) onSessionExpired()
                null
            }
        }
    }
    return shelves
}

/**
 * « Collection : <name> »: the posters of the library grid, the page's film [itemId] marked
 * « Ce film » and OK on it doing nothing. [focus] is the poster a film was opened from: it
 * takes the focus again once shown.
 */
@Composable
internal fun CollectionRow(
    shelf: CollectionShelf,
    itemId: String,
    client: EmbyClient,
    images: ImageLoader,
    focus: String?,
    onOpen: (BaseItemDto) -> Unit,
) {
    val start = shelf.titles.indexOfFirst { it.id == focus }
    // Starts scrolled to that poster, else to this film: an item off screen cannot take focus
    val row = remember { LazyListState(firstVisibleItemIndex = start.takeIf { it >= 0 } ?: shelf.titles.indexOfFirst { it.id == itemId }.coerceAtLeast(0)) }
    val requester = remember { FocusRequester() }
    Column(Modifier.padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Collection : ${shelf.ref.name}",
            style = MaterialTheme.typography.titleLarge,
            color = Palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        LazyRow(state = row, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(shelf.titles, key = { it.id }) { item ->
                val current = item.id == itemId
                Column(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ItemCard(item, landscape = false, client, images, if (item.id == focus) Modifier.focusRequester(requester) else Modifier) {
                        if (!current) onOpen(item)
                    }
                    if (current) Text("Ce film", color = Palette.accent, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    if (start >= 0) {
        // Back from that film's page; one frame later, once laid out
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { requester.requestFocus() }
        }
    }
}
