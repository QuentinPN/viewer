package io.github.quentinpn.viewer.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.ItemsOptions
import io.github.quentinpn.viewer.tv.emby.SortKey
import io.github.quentinpn.viewer.tv.emby.WatchFilter

/** Icons of the grid's right rail, top to bottom (ADR 0099); [Reset] only when something differs. */
internal enum class RailAction { Sort, Watch, Favorites, Filters, Random, Reset }

/** True when « Réinitialiser » would change something: sort, direction or a filter. */
internal fun canReset(options: ItemsOptions): Boolean = resetOptions(options) != options

/** The icons shown for [options]: all of them, « Réinitialiser » only when it would do something. */
internal fun railActions(options: ItemsOptions): List<RailAction> =
    if (canReset(options)) RailAction.entries else RailAction.entries - RailAction.Reset

/** An icon drawn in the accent: its filter narrows the grid, or the sort is not the default one. */
internal fun isActive(action: RailAction, options: ItemsOptions): Boolean = when (action) {
    RailAction.Sort -> options.sort != SortKey.Name || options.descending
    RailAction.Watch -> options.watch != WatchFilter.All
    RailAction.Favorites -> options.favorites
    RailAction.Filters -> options.genre != null || options.decade != null
    RailAction.Random, RailAction.Reset -> false
}

/** What the label beside a focused icon says, and what TalkBack reads on it. */
internal fun railLabel(action: RailAction, options: ItemsOptions): String = when (action) {
    RailAction.Sort -> "Tri : ${sortLabel(options.sort)}, ${directionLabel(options)}"
    RailAction.Watch -> "Visionnage : ${watchLabel(options.watch)}"
    RailAction.Favorites -> if (options.favorites) "Favoris seulement : oui" else "Favoris seulement : non"
    RailAction.Filters -> "Filtres : " + (listOfNotNull(options.genre, options.decade?.let { "années $it" }).joinToString(", ").ifEmpty { "aucun" })
    RailAction.Random -> "Aléatoire"
    RailAction.Reset -> "Réinitialiser"
}

/** The counter under the grid, « 12 | 239 »; null before a card has the focus or while the count is unknown. */
internal fun positionLabel(focused: Int, total: Int): String? =
    if (focused < 0 || total <= 0) null else "${(focused + 1).coerceAtMost(total)} | $total"

internal fun sortLabel(key: SortKey) = when (key) {
    SortKey.Name -> "Titre"
    SortKey.DateAdded -> "Date d'ajout"
    SortKey.Year -> "Année"
    SortKey.Rating -> "Note"
}

internal fun directionLabel(options: ItemsOptions) = when {
    options.sort == SortKey.Name -> if (options.descending) "Z → A" else "A → Z"
    options.descending -> "Décroissant"
    else -> "Croissant"
}

internal fun watchLabel(watch: WatchFilter) = when (watch) {
    WatchFilter.All -> "Tous"
    WatchFilter.Unplayed -> "Non vus"
    WatchFilter.Played -> "Vus"
    WatchFilter.InProgress -> "En cours"
}

/** One line of a side panel: picking it gives [options]. */
internal data class PanelChoice(val label: String, val options: ItemsOptions, val selected: Boolean)

/** The sort keys: another key takes its usual direction, the current one is kept as is. */
internal fun sortKeyChoices(options: ItemsOptions): List<PanelChoice> = SortKey.entries.map { key ->
    val next = if (key == options.sort) options else options.copy(sort = key, descending = key.descendingByDefault)
    PanelChoice(sortLabel(key), next, key == options.sort)
}

/** Both directions of the current key, named as that key reads them. */
internal fun directionChoices(options: ItemsOptions): List<PanelChoice> = listOf(false, true).map { descending ->
    val next = options.copy(descending = descending)
    PanelChoice(directionLabel(next), next, descending == options.descending)
}

/**
 * The right rail (ADR 0099): small round icons without text, one under the other. The focused
 * one shows its [railLabel] to its left, over the grid; an [isActive] one is drawn in the accent.
 */
@Composable
internal fun IconRail(
    options: ItemsOptions,
    requesters: Map<RailAction, FocusRequester>,
    modifier: Modifier,
    onAction: (RailAction) -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (action in railActions(options)) {
            RailButton(
                icon = railIcon(action),
                label = railLabel(action, options),
                active = isActive(action, options),
                modifier = Modifier.focusRequester(requesters.getValue(action)),
            ) { onAction(action) }
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val still = reducedMotion()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (focused) {
            Text(
                label,
                color = Palette.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.background(Palette.surfaceRaised, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Surface(
            onClick = onClick,
            modifier = modifier.size(40.dp).onFocusChanged { focused = it.isFocused }.semantics {
                contentDescription = label
                selected = active
            },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Glass,
                contentColor = if (active) Palette.accent else Palette.text,
                focusedContainerColor = Palette.text,
                focusedContentColor = Color.Black,
                pressedContainerColor = Palette.text,
                pressedContentColor = Color.Black,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.1f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), inset = -RING_GAP, shape = CircleShape)),
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                // Tinted by the surface's content colour: accent when active, black when focused
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Side panel of titled sections of choices, as the genre list (ADR 0045): the focus opens on
 * the first selected line. Back closes it without a change.
 */
@Composable
internal fun ChoicePanel(sections: List<Pair<String, List<PanelChoice>>>, modifier: Modifier, onDone: (PanelChoice?) -> Unit) {
    BackHandler { onDone(null) }
    val first = remember { FocusRequester() }
    val start = sections.flatMap { it.second }.let { all -> all.firstOrNull { it.selected } ?: all.firstOrNull() }
    LazyColumn(
        modifier.fillMaxHeight().width(420.dp).background(Palette.surface),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((title, choices) in sections) {
            item(key = "title:$title") {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.textSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(choices, key = { "$title:${it.label}" }) { choice ->
                ListItem(
                    selected = choice.selected,
                    onClick = { onDone(choice) },
                    headlineContent = { Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = if (choice.selected) ({ Text("✓") }) else null,
                    modifier = if (choice === start) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
}

private fun railIcon(action: RailAction): ImageVector = when (action) {
    RailAction.Sort -> SortIcon
    RailAction.Watch -> WatchIcon
    RailAction.Favorites -> HeartIcon
    RailAction.Filters -> FilterIcon
    RailAction.Random -> ShuffleIcon
    RailAction.Reset -> ResetIcon
}

/** Stroked 24-unit icons; white, tinted by the surface they sit on. */
private fun railStrokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** Two arrows, up and down. */
private val SortIcon = railStrokeIcon("sort", "M7 20V4", "M3 8l4-4 4 4", "M17 4v16", "M13 16l4 4 4-4")

/** An eye: what has been watched. */
private val WatchIcon = railStrokeIcon("watch", "M2 12c2.5-4.7 6-7 10-7s7.5 2.3 10 7c-2.5 4.7-6 7-10 7s-7.5-2.3-10-7z", "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")

private val HeartIcon = railStrokeIcon("favorites", "M12 20c-4.5-3-8-6.3-8-10.2A4.3 4.3 0 0 1 12 7.4a4.3 4.3 0 0 1 8 2.4c0 3.9-3.5 7.2-8 10.2z")

/** A funnel: genre and years. */
private val FilterIcon = railStrokeIcon("filters", "M4 5h16l-6 7.5V19l-4 2v-8.5z")

/** Two crossing arrows. */
private val ShuffleIcon = railStrokeIcon("random", "M3 7h3c5 0 7 10 12 10h3", "M3 17h3c5 0 7-10 12-10h3", "M18 4l3 3-3 3", "M18 14l3 3-3 3")

/** A circle going back to its start. */
private val ResetIcon = railStrokeIcon("reset", "M3.5 12a8.5 8.5 0 1 0 2.5-6L3.5 8.5", "M3.5 3.5v5h5")
