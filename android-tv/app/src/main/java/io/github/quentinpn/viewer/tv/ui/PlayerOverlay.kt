package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.player.Chapter
import io.github.quentinpn.viewer.tv.player.SkipPrompt
import io.github.quentinpn.viewer.tv.player.Track
import io.github.quentinpn.viewer.tv.player.TrackType
import io.github.quentinpn.viewer.tv.player.chapterIndex
import kotlinx.coroutines.delay

private const val HIDE_AFTER_MS = 5_000L

/** Seconds before the next episode starts on its own. */
private const val NEXT_COUNTDOWN = 10

// Fade above the text of the controls: the picture shows through it
private val FADE = 96.dp

/** What the player screen shows; written on the UI thread by the player activity. */
class PlayerUi {
    var title by mutableStateOf("")
    var detail by mutableStateOf<String?>(null)
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var paused by mutableStateOf(false)

    /** Loading text or error, centred; null once the file plays. */
    var status by mutableStateOf<String?>("Chargement…")

    var controls by mutableStateOf(false)
        private set

    /** Share of the screen height under the text of the controls, from the bottom; 0 while hidden. */
    var covered by mutableFloatStateOf(0f)
        private set

    /** The track menu is open while this is not null. */
    var tracks by mutableStateOf<List<Track>?>(null)

    /** The "Épisode suivant" card is shown while this is not null. */
    var upNext by mutableStateOf<UpNext?>(null)

    /** "Passer l'intro" or "Passer le générique" during a segment (ADR 0048). */
    var skip by mutableStateOf<SkipPrompt.Button?>(null)

    /** Chapters of the item, none with fewer than two (ADR 0057). */
    var chapters by mutableStateOf<List<Chapter>>(emptyList())

    /** The "Chapitres" menu is open. */
    var chapterMenu by mutableStateOf(false)

    val menuOpen get() = tracks != null || chapterMenu

    fun closeMenus() {
        tracks = null
        chapterMenu = false
    }

    // Bumped on each key: restarts the hide delay
    private var shown by mutableIntStateOf(0)

    fun show() {
        controls = true
        shown++
    }

    fun hide() {
        controls = false
    }

    /** Before another file: nothing of the previous one stays on screen. */
    fun reset() {
        title = ""
        detail = null
        position = 0.0
        duration = 0.0
        status = "Chargement…"
        tracks = null
        upNext = null
        skip = null
        chapters = emptyList()
        chapterMenu = false
        controls = false
    }

    internal fun cover(fraction: Float) {
        covered = fraction
    }

    @Composable
    internal fun AutoHide() {
        LaunchedEffect(shown, paused, menuOpen) {
            if (controls && !paused && !menuOpen) {
                delay(HIDE_AFTER_MS)
                controls = false
            }
        }
    }
}

/** The episode offered at the end of the current one, or from the start of its credits ([atCredits]). */
data class UpNext(val series: String, val label: String, val atCredits: Boolean = false)

/** "S1 · É3 — Le titre", or the name alone without numbers. */
internal fun episodeLabel(season: Int?, episode: Int?, name: String): String {
    val code = listOfNotNull(season?.let { "S$it" }, episode?.let { "É$it" }).joinToString(" · ")
    return if (code.isEmpty()) name else if (name.isEmpty()) code else "$code — $name"
}

/** Time, progress and title over the video, the audio / subtitle and chapter menus, the skip button and the next episode card. */
@Composable
fun PlayerOverlay(
    ui: PlayerUi,
    onSelect: (TrackType, Int?) -> Unit,
    onChapter: (Double) -> Unit,
    onPlayNext: () -> Unit,
    onCancelNext: () -> Unit,
    onSkip: () -> Unit,
) {
    ui.AutoHide()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        ui.status?.let {
            Text(it, color = Palette.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
        }
        val upNext = ui.upNext
        if (upNext != null) {
            UpNextCard(upNext, onPlayNext, onCancelNext, Modifier.align(Alignment.BottomCenter))
        } else if (ui.status == null && (ui.controls || ui.paused)) {
            // Always shown while paused, so the picture never looks frozen
            Controls(ui, Modifier.align(Alignment.BottomCenter))
        }
        val skip = ui.skip
        if (skip != null && upNext == null && ui.status == null) {
            // Above the text of the controls while they show; stays when they fade
            SkipButton(skip, !ui.menuOpen, onSkip, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = maxHeight * ui.covered + 24.dp))
        }
        ui.tracks?.let { TrackMenu(it, onSelect, Modifier.align(Alignment.CenterEnd)) }
        if (ui.chapterMenu) ChapterMenu(ui.chapters, chapterIndex(ui.chapters, ui.position), onChapter, Modifier.align(Alignment.CenterEnd))
    }
}

/** Focused when it appears or a menu closes, never while a menu is open. */
@Composable
private fun SkipButton(skip: SkipPrompt.Button, focus: Boolean, onSkip: () -> Unit, modifier: Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(skip.label, focus) {
        if (!focus) return@LaunchedEffect
        withFrameNanos {}
        runCatching { requester.requestFocus() }
    }
    Button(onClick = onSkip, modifier = modifier.focusRequester(requester)) { Text(skip.label) }
}

@Composable
private fun UpNextCard(upNext: UpNext, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var left by remember(upNext) { mutableIntStateOf(NEXT_COUNTDOWN) }
    val play = remember { FocusRequester() }
    LaunchedEffect(upNext) {
        withFrameNanos {}
        runCatching { play.requestFocus() }
        while (left > 0) {
            delay(1_000)
            left--
        }
        onPlay()
    }
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.7f), 1f to Color.Black.copy(alpha = 0.9f)))
            .padding(start = 48.dp, end = 48.dp, top = FADE, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Épisode suivant", color = Palette.textSecondary, style = MaterialTheme.typography.titleSmall)
        Text(upNext.series, color = Palette.text, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(upNext.label, color = Palette.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Lecture dans $left s", color = Palette.textSecondary, style = MaterialTheme.typography.bodyMedium)
        // Empties as the countdown runs, by whole seconds
        Box(Modifier.width(320.dp).height(4.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(left / NEXT_COUNTDOWN.toFloat()).height(4.dp).background(Palette.text, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onPlay, modifier = Modifier.focusRequester(play)) { Text("Lire maintenant") }
            OutlinedButton(onClick = onCancel) { Text("Annuler") }
        }
    }
}

@Composable
private fun Controls(ui: PlayerUi, modifier: Modifier) {
    val fade = with(LocalDensity.current) { FADE.toPx() }
    DisposableEffect(Unit) { onDispose { ui.cover(0f) } }
    Column(
        modifier.fillMaxWidth()
            .onGloballyPositioned { layout ->
                val height = layout.parentLayoutCoordinates?.size?.height ?: return@onGloballyPositioned
                if (height > 0) ui.cover(1f - (layout.positionInParent().y + fade) / height)
            }
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.85f)))
            .padding(start = 48.dp, end = 48.dp, top = FADE, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(ui.title, color = Palette.text, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ui.detail?.let { Text(it, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val fraction = if (ui.duration > 0) (ui.position / ui.duration).coerceIn(0.0, 1.0).toFloat() else 0f
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Palette.text, RoundedCornerShape(3.dp)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(seconds(ui.position), color = Palette.text, style = MaterialTheme.typography.bodyLarge)
            if (ui.paused) {
                Text(
                    "En pause",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp).background(Palette.text, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (ui.duration > 0) {
                Text("−${seconds(ui.duration - ui.position)}", color = Palette.textSecondary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            "OK lecture / pause  ·  ◀ ▶ 10 s  ·  ▼ audio et sous-titres" + if (ui.chapters.isNotEmpty()) "  ·  ▲ chapitres" else "",
            color = Palette.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun seconds(value: Double) = clock((value.coerceAtLeast(0.0) * 10_000_000).toLong())

private sealed interface MenuRow {
    data class Header(val text: String) : MenuRow

    data class Choice(val type: TrackType, val id: Int?, val label: String, val selected: Boolean) : MenuRow
}

private fun menuRows(tracks: List<Track>): List<MenuRow> = buildList {
    val subs = tracks.filter { it.type == TrackType.Subtitle }
    add(MenuRow.Header("Sous-titres"))
    add(MenuRow.Choice(TrackType.Subtitle, null, "Désactivés", subs.none { it.selected }))
    subs.forEach { add(MenuRow.Choice(it.type, it.id, it.label, it.selected)) }
    val audio = tracks.filter { it.type == TrackType.Audio }
    if (audio.isNotEmpty()) {
        add(MenuRow.Header("Audio"))
        audio.forEach { add(MenuRow.Choice(it.type, it.id, it.label, it.selected)) }
    }
}

@Composable
private fun TrackMenu(tracks: List<Track>, onSelect: (TrackType, Int?) -> Unit, modifier: Modifier) {
    val rows = menuRows(tracks)
    val first = remember { FocusRequester() }
    // Focus starts on the subtitle in use
    val start = rows.indexOfFirst { it is MenuRow.Choice && it.type == TrackType.Subtitle && it.selected }
    LazyColumn(
        modifier.fillMaxHeight().width(460.dp).background(Palette.surface.copy(alpha = 0.95f)),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(rows) { row ->
            when (row) {
                is MenuRow.Header -> Text(
                    row.text,
                    color = Palette.textSecondary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
                )
                is MenuRow.Choice -> ListItem(
                    selected = row.selected,
                    onClick = { onSelect(row.type, row.id) },
                    headlineContent = { Text(row.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = if (row.selected) ({ Text("✓") }) else null,
                    modifier = if (rows.indexOf(row) == start) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
}

/**
 * One row per chapter with its start, the playing one marked (ADR 0057). Focus
 * starts on it, the list scrolled there, so it is composed when asked to focus.
 */
@Composable
private fun ChapterMenu(chapters: List<Chapter>, current: Int, onChapter: (Double) -> Unit, modifier: Modifier) {
    val start = remember { current.coerceAtLeast(0) }
    val focus = remember { FocusRequester() }
    // Row i is item i + 1, under the title: the row before stays in view
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    LazyColumn(
        modifier.fillMaxHeight().width(460.dp).background(Palette.surface.copy(alpha = 0.95f)),
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                "Chapitres",
                color = Palette.textSecondary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(chapters) { i, chapter ->
            ListItem(
                selected = i == current,
                onClick = { onChapter(chapter.start) },
                headlineContent = { Text(chapter.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text(seconds(chapter.start)) },
                modifier = if (i == start) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
}
