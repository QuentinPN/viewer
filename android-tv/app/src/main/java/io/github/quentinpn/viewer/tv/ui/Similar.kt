package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException

/** Posters of the « Titres similaires » row (ADR 0063). */
internal const val SIMILAR_LIMIT = 12

/** Pages deep enough for any chain of similar titles; the oldest is forgotten past it. */
internal const val MAX_PAGES = 10

/** A film or series page in the navigation of `MainActivity`; similar titles stack them (ADR 0063). */
internal sealed interface DetailPage {
    data class Movie(val id: String) : DetailPage

    data class Series(val target: SeriesTarget) : DetailPage
}

/** [page] over [pages], Back going down to the one below. */
internal fun pushPage(pages: List<DetailPage>, page: DetailPage): List<DetailPage> = (pages + page).takeLast(MAX_PAGES)

/** The top page swapped for [page], as Back from an episode names its season and card (ADR 0060). */
internal fun replaceTop(pages: List<DetailPage>, page: DetailPage): List<DetailPage> = pages.dropLast(1) + page

/** The page a similar title opens; null for any other type. */
internal fun similarPage(item: BaseItemDto): DetailPage? = when (item.type) {
    "Movie" -> DetailPage.Movie(item.id)
    "Series" -> DetailPage.Series(SeriesTarget(item.id))
    else -> null
}

/** Films and series only, without the page's own title nor duplicates, [limit] at most. */
internal fun similarTitles(items: List<BaseItemDto>, itemId: String, limit: Int = SIMILAR_LIMIT): List<BaseItemDto> =
    items.filter { it.id != itemId && similarPage(it) != null }.distinctBy { it.id }.take(limit)

/**
 * The similar titles of [itemId], read once [ready] (after the page itself). Empty on a
 * failure, so the row hides and the page stays; an expired session is still handled.
 */
@Composable
internal fun rememberSimilar(client: EmbyClient, userId: String, itemId: String, ready: Boolean, onSessionExpired: () -> Unit): List<BaseItemDto> {
    var similar by remember(itemId) { mutableStateOf(emptyList<BaseItemDto>()) }
    LaunchedEffect(itemId, ready) {
        if (!ready) return@LaunchedEffect
        similar = try {
            similarTitles(client.similar(userId, itemId, SIMILAR_LIMIT).items, itemId)
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            emptyList()
        }
    }
    return similar
}

/** « Titres similaires »: the posters of the library grid; nothing at all when there are none. */
@Composable
internal fun SimilarRow(items: List<BaseItemDto>, client: EmbyClient, images: ImageLoader, onOpen: (BaseItemDto) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Titres similaires", style = MaterialTheme.typography.titleLarge, color = Palette.text, modifier = Modifier.padding(horizontal = 48.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(items, key = { it.id }) { item ->
                ItemCard(item, landscape = false, client, images, Modifier) { onOpen(item) }
            }
        }
    }
}
