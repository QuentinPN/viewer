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
 * The grid's choices for [genre]: the saved sort and direction, but no watch or favourite
 * filter, which could hide the very title the genre was opened from. Null for a name the
 * server cannot be asked for.
 */
internal fun genreOptions(saved: ItemsOptions, genre: String): ItemsOptions? =
    if (isUsableGenre(genre)) ItemsOptions(sort = saved.sort, descending = saved.descending, genre = genre) else null

/**
 * The [grid] over [pages], the film or series below remembering the chip [genre] so Back
 * focuses it again; a person card or collection poster remembered earlier is forgotten.
 */
internal fun openGenre(pages: List<DetailPage>, grid: DetailPage.Grid, genre: String): List<DetailPage> {
    val below = when (val top = pages.lastOrNull()) {
        is DetailPage.Movie -> replaceTop(pages, top.copy(person = null, collection = null, genre = genre))
        is DetailPage.Series -> replaceTop(pages, top.copy(person = null, genre = genre))
        else -> pages
    }
    return pushPage(below, grid)
}

/**
 * « Genres » as a line of the facts, a glass pill per genre (ADR 0067); OK opens the
 * library's grid on it. [focus] takes the focus once placed, as Back from that grid.
 */
@Composable
internal fun GenreChips(chips: List<String>, focus: String?, onOpen: (String) -> Unit) {
    if (chips.isEmpty()) return
    val requesters = remember(chips) { chips.associateWith { FocusRequester() } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Genres", color = Palette.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(100.dp))
        chips.forEach { genre ->
            GlassButton(
                onClick = { onOpen(genre) },
                modifier = Modifier.focusRequester(requesters.getValue(genre)).semantics { contentDescription = "Genre $genre : voir les titres" },
            ) { PillText(genre) }
        }
    }
    LaunchedEffect(requesters, focus) {
        val requester = focus?.let { requesters[it] } ?: return@LaunchedEffect
        withFrameNanos {}
        runCatching { requester.requestFocus() }
    }
}
