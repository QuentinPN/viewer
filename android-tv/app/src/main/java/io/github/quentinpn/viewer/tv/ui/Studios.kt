package io.github.quentinpn.viewer.tv.ui

import androidx.compose.runtime.Composable
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.isUsableStudio

/** Studios a detail page can open (ADR 0087): three at most, as the genres. */
internal const val STUDIO_CHIPS_LIMIT = 3

/**
 * The studios or networks of [item] that open a grid, in the server's order, by name: the
 * `Studios` filter takes names, and `Studios[].Id` is a number on Emby 4.10. A name the
 * filter cannot be asked for (`|`, blank, long, control) has no chip; no duplicates.
 */
internal fun studioChips(item: BaseItemDto): List<String> =
    item.studios.map { it.name }.filter(::isUsableStudio).distinct().take(STUDIO_CHIPS_LIMIT)

/**
 * The grid's choices for [studio], as for a genre (ADR 0079): the saved sort and direction, no
 * other filter. Null for a name the server cannot be asked for.
 */
internal fun studioOptions(saved: ItemsOptions, studio: String): ItemsOptions? =
    if (isUsableStudio(studio)) ItemsOptions(sort = saved.sort, descending = saved.descending, studio = studio) else null

/** The grid's title: the studio it was opened on, else the library (ADR 0087). */
internal fun gridTitle(library: Library, options: ItemsOptions): String = options.studio ?: library.name

/** « Réinitialiser » drops the toolbar's choices, not the studio the grid is about. */
internal fun resetOptions(options: ItemsOptions): ItemsOptions = ItemsOptions(studio = options.studio)

/**
 * The [grid] over [pages], the film or series below remembering the chip [studio] so Back
 * focuses it again; a person card, collection poster or genre chip remembered earlier is forgotten.
 */
internal fun openStudio(pages: List<DetailPage>, grid: DetailPage.Grid, studio: String): List<DetailPage> {
    val below = when (val top = pages.lastOrNull()) {
        is DetailPage.Movie -> replaceTop(pages, top.copy(person = null, collection = null, genre = null, studio = studio))
        is DetailPage.Series -> replaceTop(pages, top.copy(person = null, genre = null, studio = studio))
        else -> pages
    }
    return pushPage(below, grid)
}

/** The genre and studio chips of a detail page, with the one to focus back and what OK does. */
internal class ChipLines(
    val genres: List<String>,
    val focusGenre: String?,
    val onOpenGenre: (String) -> Unit,
    val studios: List<String>,
    val focusStudio: String?,
    val onOpenStudio: (String) -> Unit,
)

/** « Studio » or « Diffusion » as a line of pills under the genres (ADR 0087); OK opens the grid. */
@Composable
internal fun StudioChips(item: BaseItemDto, chips: List<String>, focus: String?, onOpen: (String) -> Unit) {
    val label = studioLabel(item)
    ChipLine(label, chips, focus, { "$label $it : voir les titres" }, onOpen)
}
