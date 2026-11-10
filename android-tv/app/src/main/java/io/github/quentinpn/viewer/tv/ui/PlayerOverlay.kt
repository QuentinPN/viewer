package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.player.Chapter
import io.github.quentinpn.viewer.tv.player.DelayChange
import io.github.quentinpn.viewer.tv.player.DelayKind
import io.github.quentinpn.viewer.tv.player.Delays
import io.github.quentinpn.viewer.tv.player.FitChange
import io.github.quentinpn.viewer.tv.player.InfoRow
import io.github.quentinpn.viewer.tv.player.NORMAL_SPEED
import io.github.quentinpn.viewer.tv.player.Night
import io.github.quentinpn.viewer.tv.player.SleepChange
import io.github.quentinpn.viewer.tv.player.SleepChoice
import io.github.quentinpn.viewer.tv.player.SleepItem
import io.github.quentinpn.viewer.tv.player.SleepTimer
import io.github.quentinpn.viewer.tv.player.SpeedChange
import io.github.quentinpn.viewer.tv.player.SkipPrompt
import io.github.quentinpn.viewer.tv.player.Track
import io.github.quentinpn.viewer.tv.player.TrackType
import io.github.quentinpn.viewer.tv.player.VideoFit
import io.github.quentinpn.viewer.tv.player.chapterIndex
import io.github.quentinpn.viewer.tv.player.endLabel
import io.github.quentinpn.viewer.tv.player.formatClock
import io.github.quentinpn.viewer.tv.player.formatDelay
import io.github.quentinpn.viewer.tv.player.formatSpeed
import io.github.quentinpn.viewer.tv.player.nightNote
import io.github.quentinpn.viewer.tv.player.nightValue
import io.github.quentinpn.viewer.tv.player.sleepBadge
import io.github.quentinpn.viewer.tv.player.sleepValue
import io.github.quentinpn.viewer.tv.player.speedBadge
import io.github.quentinpn.viewer.tv.player.untilNextMinute
import kotlinx.coroutines.delay
import java.time.ZoneId

private const val HIDE_AFTER_MS = 5_000L

/** Seconds before the next episode starts on its own. */
private const val NEXT_COUNTDOWN = 10

// Fade above the text of the controls: the picture shows through it
private val FADE = 96.dp

/** The seek thumbnail stays this long after the last ◀ ▶ (ADR 0069). */
private const val SEEK_PREVIEW_MS = 1_500L

// Seek thumbnail: about a quarter of a 960 dp wide TV screen, above the bar's thumb
private val PREVIEW_WIDTH = 240.dp
private val PREVIEW_GAP = 14.dp

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

    /** The "Informations de lecture" panel is open while this is not null (ADR 0061); refreshed by the player. */
    var info by mutableStateOf<List<InfoRow>?>(null)

    /** Subtitle and audio delays, as mpv holds them; this playback only (ADR 0062). */
    var delays by mutableStateOf(Delays())

    /** Playback speed as mpv holds it; this playback only (ADR 0072). */
    var speed by mutableDoubleStateOf(NORMAL_SPEED)

    /** How the picture fills the screen, as mpv holds it; this playback only (ADR 0078). */
    var fit by mutableStateOf(VideoFit.Original)

    /** Night mode, from Réglages or the menu (ADR 0072). */
    var night by mutableStateOf(Night())

    /** Wall clock of the clock and « Fin à » (ADR 0073): set on each position, and at each minute while the controls show. */
    var now by mutableLongStateOf(System.currentTimeMillis())

    /** Episode or film, for « Fin de l'épisode » / « Fin du film »; null for anything else or until known (ADR 0073). */
    var item by mutableStateOf<SleepItem?>(null)

    /** The sleep timer: the whole player session, kept from one episode to the next (ADR 0073). */
    var sleep by mutableStateOf(SleepTimer())

    /** Over the paused picture once the timer has run out; gone when playback resumes. */
    var notice by mutableStateOf<String?>(null)

    /** Target of the last ◀ ▶ seek while its thumbnail may show (ADR 0069); null once the seek settles. */
    var seekTarget by mutableStateOf<Double?>(null)
        private set

    /** The thumbnail nearest [seekTarget], once decoded; none when the server has none. */
    var seekFrame by mutableStateOf<SeekFrame?>(null)

    // Bumped on each seek: restarts the delay before the thumbnail goes
    private var seeks by mutableIntStateOf(0)

    fun seeking(target: Double) {
        seekTarget = target
        seeks++
    }

    val menuOpen get() = tracks != null || chapterMenu || info != null

    fun closeMenus() {
        tracks = null
        chapterMenu = false
        info = null
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
        info = null
        delays = Delays()
        speed = NORMAL_SPEED
        fit = VideoFit.Original
        item = null
        notice = null
        seekTarget = null
        seekFrame = null
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
        LaunchedEffect(seeks) {
            if (seekTarget != null) {
                delay(SEEK_PREVIEW_MS)
                seekTarget = null
            }
        }
    }
}

/** A decoded seek thumbnail; [index] is its BIF image, so the same one is not decoded twice (ADR 0069). */
class SeekFrame(val index: Int, val image: ImageBitmap)

/** The episode offered at the end of the current one, or from the start of its credits ([atCredits]). */
data class UpNext(val series: String, val label: String, val atCredits: Boolean = false)

/** "S1 · É3 — Le titre", or the name alone without numbers. */
internal fun episodeLabel(season: Int?, episode: Int?, name: String): String {
    val code = listOfNotNull(season?.let { "S$it" }, episode?.let { "É$it" }).joinToString(" · ")
    return if (code.isEmpty()) name else if (name.isEmpty()) code else "$code — $name"
}

/** Time, progress, title, clock and end time over the video, the audio / subtitle / delay, chapter and information menus, the skip button and the next episode card. */
@Composable
fun PlayerOverlay(
    ui: PlayerUi,
    onSelect: (TrackType, Int?) -> Unit,
    onChapter: (Double) -> Unit,
    onPlayNext: () -> Unit,
    onCancelNext: () -> Unit,
    onSkip: () -> Unit,
    onInfo: () -> Unit,
    onDelay: (DelayKind, DelayChange) -> Unit,
    onSpeed: (SpeedChange) -> Unit,
    onFit: (FitChange) -> Unit,
    onNight: () -> Unit,
    onSleep: (SleepChange) -> Unit,
) {
    ui.AutoHide()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        ui.status?.let {
            Text(it, color = Palette.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
        }
        val notice = ui.notice
        if (notice != null && ui.status == null) {
            Text(
                notice,
                color = Palette.text,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center).background(Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
            )
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
        ui.tracks?.let {
            TrackMenu(it, MenuState(ui.delays, ui.speed, ui.fit, ui.night, ui.sleep.choice, ui.item), MenuActions(onSelect, onDelay, onSpeed, onFit, onNight, onSleep, onInfo), Modifier.align(Alignment.CenterEnd))
        }
        ui.info?.let { InfoMenu(it, Modifier.align(Alignment.CenterEnd)) }
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
        // The clock moves on with each position; paused, the minute's start moves it on (ADR 0073)
        LaunchedEffect(Unit) {
            while (true) {
                delay(untilNextMinute(System.currentTimeMillis()))
                ui.now = System.currentTimeMillis()
            }
        }
        val zone = remember { ZoneId.systemDefault() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.title, color = Palette.text, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatClock(ui.now, zone), color = Palette.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp))
        }
        ui.detail?.let { Text(it, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        val fraction = if (ui.duration > 0) (ui.position / ui.duration).coerceIn(0.0, 1.0).toFloat() else 0f
        BoxWithConstraints(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Palette.text, RoundedCornerShape(3.dp)))
            val target = ui.seekTarget
            val frame = ui.seekFrame
            if (target != null && frame != null && ui.duration > 0) {
                val at = (target / ui.duration).coerceIn(0.0, 1.0).toFloat()
                val start = previewStart(at, maxWidth.value, PREVIEW_WIDTH.value).dp
                // Drawn over the title above the bar, without moving it; no animation, it jumps with the thumb
                SeekPreview(
                    frame,
                    seconds(target),
                    Modifier.align(Alignment.BottomStart)
                        .wrapContentSize(Alignment.BottomStart, unbounded = true)
                        .offset(x = start, y = -(6.dp + PREVIEW_GAP)),
                )
            }
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
            // Only away from 1x: a reminder that the speed was changed in the menu (ADR 0072)
            // Likewise away from « Original »: the picture format chosen in the menu (ADR 0078)
            // As the speed's: the timer set in the menu, and what is left of it (ADR 0073)
            listOfNotNull(speedBadge(ui.speed), ui.fit.badge, sleepBadge(ui.sleep, ui.now, ui.item)).forEach {
                Text(
                    it,
                    color = Palette.text,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (ui.duration > 0) {
                Text("−${seconds(ui.duration - ui.position)}", color = Palette.textSecondary, style = MaterialTheme.typography.bodyLarge)
            }
            endLabel(ui.now, ui.position, ui.duration, ui.speed, zone)?.let {
                Text(it, color = Palette.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
            }
        }
        Text(
            "OK lecture / pause  ·  ◀ ▶ 10 s  ·  ▼ audio, sous-titres, vitesse, format, minuterie et infos" + if (ui.chapters.isNotEmpty()) "  ·  ▲ chapitres" else "",
            color = Palette.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The thumbnail at the seek target, its time under it, rounded as the menus (ADR 0069). */
@Composable
private fun SeekPreview(frame: SeekFrame, time: String, modifier: Modifier) {
    val ratio = frame.image.width.toFloat() / frame.image.height.coerceAtLeast(1)
    Column(
        modifier.width(PREVIEW_WIDTH).background(Palette.surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp)).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            frame.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(ratio.takeIf { it > 0f } ?: (16f / 9f)).clip(RoundedCornerShape(7.dp)),
        )
        Text(time, color = Palette.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    }
}

/**
 * Left edge of the seek thumbnail, centred on [fraction] of a bar [bar] wide and kept
 * within it; all three in the same unit.
 */
internal fun previewStart(fraction: Float, bar: Float, preview: Float): Float =
    (fraction.coerceIn(0f, 1f) * bar - preview / 2).coerceIn(0f, (bar - preview).coerceAtLeast(0f))

internal fun seconds(value: Double) =clock((value.coerceAtLeast(0.0) * 10_000_000).toLong())

private sealed interface MenuRow {
    data class Header(val text: String) : MenuRow

    data class Choice(val type: TrackType, val id: Int?, val label: String, val selected: Boolean) : MenuRow

    /** ◀ ▶ shift by a tenth of a second, OK goes back to none (ADR 0062). */
    data class Delay(val kind: DelayKind, val seconds: Double) : MenuRow

    /** ◀ ▶ step through the offered speeds, OK goes back to « Normale » (ADR 0072). */
    data class Speed(val speed: Double) : MenuRow

    /** ◀ ▶ step through the picture formats, OK goes back to « Original » (ADR 0078). */
    data class Fit(val fit: VideoFit) : MenuRow

    /** OK turns night mode on or off (ADR 0072). */
    data class NightMode(val night: Night) : MenuRow

    /** ◀ ▶ step through the timer's values, OK goes to the next one and wraps (ADR 0073). */
    data class Sleep(val choice: SleepChoice, val item: SleepItem?) : MenuRow

    /** Last row: opens "Informations de lecture" in place of the menu (ADR 0061). */
    data object Info : MenuRow
}

/** What the rows of the track menu show beyond the tracks. */
private class MenuState(val delays: Delays, val speed: Double, val fit: VideoFit, val night: Night, val sleep: SleepChoice, val item: SleepItem?)

private class MenuActions(
    val select: (TrackType, Int?) -> Unit,
    val delay: (DelayKind, DelayChange) -> Unit,
    val speed: (SpeedChange) -> Unit,
    val fit: (FitChange) -> Unit,
    val night: () -> Unit,
    val sleep: (SleepChange) -> Unit,
    val info: () -> Unit,
)

/** ◀ and ▶ on a row: [left] or [right], held down they repeat; other keys go on as usual. */
private fun <T> Modifier.leftRight(left: T, right: T, onChange: (T) -> Unit) = onPreviewKeyEvent {
    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val change = when (it.key) {
        Key.DirectionLeft -> left
        Key.DirectionRight -> right
        else -> return@onPreviewKeyEvent false
    }
    onChange(change)
    true
}

private fun menuRows(tracks: List<Track>, state: MenuState): List<MenuRow> = buildList {
    val delays = state.delays
    val subs = tracks.filter { it.type == TrackType.Subtitle }
    add(MenuRow.Header("Sous-titres"))
    add(MenuRow.Choice(TrackType.Subtitle, null, "Désactivés", subs.none { it.selected }))
    subs.forEach { add(MenuRow.Choice(it.type, it.id, it.label, it.selected)) }
    val audio = tracks.filter { it.type == TrackType.Audio }
    if (audio.isNotEmpty()) {
        add(MenuRow.Header("Audio"))
        audio.forEach { add(MenuRow.Choice(it.type, it.id, it.label, it.selected)) }
    }
    add(MenuRow.Header("Synchronisation"))
    // Shifting subtitles that do not show would change nothing visible
    if (subs.any { it.selected }) add(MenuRow.Delay(DelayKind.Subtitle, delays.subtitle))
    add(MenuRow.Delay(DelayKind.Audio, delays.audio))
    add(MenuRow.Header("Lecture"))
    add(MenuRow.Speed(state.speed))
    add(MenuRow.Fit(state.fit))
    add(MenuRow.NightMode(state.night))
    add(MenuRow.Sleep(state.sleep, state.item))
    add(MenuRow.Info)
}

@Composable
private fun TrackMenu(tracks: List<Track>, state: MenuState, actions: MenuActions, modifier: Modifier) {
    val rows = menuRows(tracks, state)
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
                    onClick = { actions.select(row.type, row.id) },
                    headlineContent = { Text(row.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = if (row.selected) ({ Text("✓") }) else null,
                    modifier = if (rows.indexOf(row) == start) Modifier.focusRequester(first) else Modifier,
                )
                is MenuRow.Delay -> ListItem(
                    selected = false,
                    onClick = { actions.delay(row.kind, DelayChange.Reset) },
                    headlineContent = { Text(row.kind.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("◀ ▶ 0,1 s  ·  OK remet à zéro", maxLines = 1) },
                    trailingContent = { Text(formatDelay(row.seconds)) },
                    // Held down, the key repeats: one tenth per repeat; ▲ ▼ still move between rows
                    modifier = Modifier.leftRight(DelayChange.Earlier, DelayChange.Later) { actions.delay(row.kind, it) },
                )
                is MenuRow.Speed -> ListItem(
                    selected = false,
                    onClick = { actions.speed(SpeedChange.Reset) },
                    headlineContent = { Text("Vitesse", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("◀ ▶ change  ·  OK revient à Normale", maxLines = 1) },
                    trailingContent = { Text(formatSpeed(row.speed)) },
                    modifier = Modifier.leftRight(SpeedChange.Slower, SpeedChange.Faster, actions.speed),
                )
                is MenuRow.Fit -> ListItem(
                    selected = false,
                    onClick = { actions.fit(FitChange.Reset) },
                    headlineContent = { Text("Format", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("◀ ▶ change  ·  OK revient à Original", maxLines = 1) },
                    trailingContent = { Text(row.fit.label) },
                    modifier = Modifier.leftRight(FitChange.Previous, FitChange.Next, actions.fit),
                )
                is MenuRow.NightMode -> ListItem(
                    selected = false,
                    onClick = actions.night,
                    headlineContent = { Text("Mode nuit", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(nightNote(row.night), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text(nightValue(row.night)) },
                )
                is MenuRow.Sleep -> ListItem(
                    selected = false,
                    onClick = { actions.sleep(SleepChange.Cycle) },
                    headlineContent = { Text("Minuterie", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("◀ ▶ ou OK change  ·  met ensuite la lecture en pause", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text(sleepValue(row.choice, row.item)) },
                    modifier = Modifier.leftRight(SleepChange.Previous, SleepChange.Next, actions.sleep),
                )
                MenuRow.Info -> ListItem(
                    selected = false,
                    onClick = actions.info,
                    headlineContent = { Text("Informations de lecture", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.padding(top = 16.dp),
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

/**
 * "Informations de lecture" (ADR 0061): one row per value the player knows, refreshed
 * about once per second. Rows take the focus, so ▲ ▼ scroll a list taller than the screen
 * and the skip button never takes it; OK does nothing, Retour closes the panel.
 */
@Composable
private fun InfoMenu(rows: List<InfoRow>, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LazyColumn(
        modifier.fillMaxHeight().width(460.dp).background(Palette.surface.copy(alpha = 0.95f)),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Text(
                "Informations de lecture",
                color = Palette.textSecondary,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        // Keyed by label: the focused row keeps the focus when the values refresh
        itemsIndexed(rows, key = { _, row -> row.label }) { i, row ->
            DenseListItem(
                selected = false,
                onClick = {},
                overlineContent = { Text(row.label) },
                headlineContent = { Text(row.value, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
}
