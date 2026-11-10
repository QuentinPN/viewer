package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.isUsableGenre

/** Genres a detail page can open (ADR 0079): as on the year line, three at most. */
internal const val GENRE_CHIPS_LIMIT = 3

/**
 * The genres of [item] that open a grid: the year line's ones, in the server's order, without
 * a name the `Genres` filter refuses (ADR 0045) nor duplicates.
 */
internal fun genreChips(item: BaseItemDto): List<String> =
    item.genres.filter(::isUsableGenre).distinct().take(GENRE_CHIPS_LIMIT)

/** The year line's genres: none when the chips below show them already (ADR 0079). */
internal fun yearLineGenres(item: BaseItemDto, chips: List<String>): String? = if (chips.isEmpty()) genres(item) else null

/**
 * The library a genre of a film (`Movie`) or a series (`Series`) opens: the one « Films » or
 * « Séries » opens from the home bar (ADR 0064); null for another type or without one.
 */
internal fun genreLibrary(libraries: List<Library>, itemType: String): Library? = when (itemType) {
    "Movie" -> sectionLibrary(libraries, "Movie", "Films")
    "Series" -> sectionLibrary(libraries, "Series", "Séries")
    else -> null
}

/**
 * The library a genre or studio of a title opens (ADR 0093): the one of [itemType] among
 * [ancestorIds], the parents of the title, so an anime opens « Anime » and not « Séries TV »;
 * else, ancestors unknown or none of them a home library, [genreLibrary]'s choice.
 */
internal fun titleLibrary(libraries: List<Library>, itemType: String, ancestorIds: List<String>?): Library? {
    val fallback = genreLibrary(libraries, itemType) ?: return null
    val ids = ancestorIds?.toSet() ?: return fallback
    return libraries.firstOrNull { it.itemTypes == fallback.itemTypes && it.id in ids } ?: fallback
}

/**
 * The ids of [itemId]'s parents (ADR 0093), or null when they cannot be read: the chips then
 * open [genreLibrary]'s choice. An expired session is left to the page, which reads it too.
 */
internal suspend fun ancestorIdsOrNull(client: EmbyClient, userId: String, itemId: String): List<String>? = try {
    client.ancestors(userId, itemId).map { it.id }
} catch (_: EmbyException) {
    null
}

/**
 * The grid's choices for [genre]: the saved sort and direction, but no watch or favourite
 * filter, which could hide the very title the genre was opened from. Null for a name the
 * server cannot be asked for.
 */
internal fun genreOptions(saved: ItemsOptions, genre: String): ItemsOptions? =
    if (isUsableGenre(genre)) ItemsOptions(sort = saved.sort, descending = saved.descending, genre = genre) else null

/**
 * The [grid] over [pages], the film or series below remembering the chip [genre] so Back
 * focuses it again; a person card, collection poster or studio chip remembered earlier is forgotten.
 */
internal fun openGenre(pages: List<DetailPage>, grid: DetailPage.Grid, genre: String): List<DetailPage> {
    val below = when (val top = pages.lastOrNull()) {
        is DetailPage.Movie -> replaceTop(pages, top.copy(person = null, collection = null, genre = genre, studio = null))
        is DetailPage.Series -> replaceTop(pages, top.copy(person = null, genre = genre, studio = null))
        else -> pages
    }
    return pushPage(below, grid)
}

/**
 * « Genres » as a line of the facts, a glass pill per genre (ADR 0067); OK opens the
 * library's grid on it. [focus] takes the focus once placed, as Back from that grid.
 */
@Composable
internal fun GenreChips(chips: List<String>, focus: String?, onOpen: (String) -> Unit) =
    ChipLine("Genres", chips, focus, { "Genre $it : voir les titres" }, onOpen)

/**
 * A [label] in the facts' column then a glass pill per name of [chips], each opening a grid;
 * shared by genres and studios (ADR 0079, 0087). [focus] takes the focus once placed.
 */
@Composable
internal fun ChipLine(label: String, chips: List<String>, focus: String?, describe: (String) -> String, onOpen: (String) -> Unit) {
    if (chips.isEmpty()) return
    val requesters = remember(chips) { chips.associateWith { FocusRequester() } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(100.dp))
        chips.forEach { name ->
            GlassButton(
                onClick = { onOpen(name) },
                modifier = Modifier.focusRequester(requesters.getValue(name)).semantics { contentDescription = describe(name) },
            ) { PillText(name) }
        }
    }
    LaunchedEffect(requesters, focus) {
        val requester = focus?.let { requesters[it] } ?: return@LaunchedEffect
        withFrameNanos {}
        runCatching { requester.requestFocus() }
    }
}
