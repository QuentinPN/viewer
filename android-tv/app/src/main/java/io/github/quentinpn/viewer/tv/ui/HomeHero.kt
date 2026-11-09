package io.github.quentinpn.viewer.tv.ui

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.HeroTitle
import kotlinx.coroutines.launch

/** Height of the first shelf's cards showing under the hero, its title hidden (ADR 0064). */
internal val HERO_PEEK = 76.dp

/** Hero buttons; the one opened last takes the focus back on return (ADR 0059). */
internal enum class HeroButton { Play, Info }

/** Index after a step of [delta], wrapping around both ends (Windows `stepIndex`). */
internal fun stepIndex(index: Int, delta: Int, size: Int): Int = if (size <= 0) 0 else Math.floorMod(index + delta, size)

/**
 * Title to show once the list is read again (Windows `keptIndex`): the one shown before
 * if it is still there, else the same place, within bounds.
 */
internal fun keptIndex(titles: List<HeroTitle>, shownId: String?, shownIndex: Int): Int {
    val found = titles.indexOfFirst { it.id == shownId }
    if (found >= 0) return found
    return shownIndex.coerceIn(0, (titles.size - 1).coerceAtLeast(0))
}

/** Label of the main button: "Reprendre à 12:34", "Lecture", or none when there is nothing to play. */
internal fun heroPlayLabel(title: HeroTitle): String? = when {
    title.playId == null -> null
    title.startTicks > 0 -> "Reprendre à ${clock(title.startTicks)}"
    else -> "Lecture"
}

/** Line under the name: `S4 · É1 · La Rançon` for an episode, else year, length and rating. */
internal fun heroFacts(title: HeroTitle): String {
    title.episode?.let { e ->
        val code = e.season?.let { s -> e.index?.let { i -> "S$s · É$i" } }
        return listOfNotNull(code, e.name.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }
    return listOfNotNull(
        title.year?.toString(),
        title.runTimeTicks?.takeIf { it > 0 }?.let(::length),
        title.officialRating,
    ).joinToString("  ·  ")
}

/** Watched share and « Reste 22 min » under a resume point; none without one or without a length. */
internal fun heroRemaining(title: HeroTitle): Pair<Float, String>? {
    val total = title.playLength?.takeIf { it > 0 } ?: return null
    if (title.startTicks <= 0 || title.startTicks >= total) return null
    return (title.startTicks.toFloat() / total) to "Reste ${length(total - title.startTicks)}"
}

/**
 * Home hero (ADR 0059, from Windows ADR 0015; laid out per ADR 0064): one title at a time
 * over its backdrop, the home's bar on top. Titles change only on request: Left on the
 * first button shows the previous one, Right on the last button the next one, wrapping
 * around; no automatic rotation. [focus] goes on [focusOn] when the home puts its focus
 * on the hero.
 */
@Composable
internal fun HomeHero(
    titles: List<HeroTitle>,
    memory: HomeMemory,
    client: EmbyClient,
    images: ImageLoader,
    focus: FocusRequester?,
    focusOn: HeroButton,
    onPlay: (HeroTitle) -> Unit,
    onInfo: (HeroTitle) -> Unit,
    /** Down from the bar lands here: the main button, else « Plus d'infos ». */
    main: FocusRequester,
    /** Up from the buttons: the bar's « Accueil ». */
    up: FocusRequester,
    /** The home's bar, laid over the top of the backdrop. */
    header: @Composable () -> Unit,
) {
    if (titles.isEmpty()) return
    val index = keptIndex(titles, memory.heroId, memory.heroIndex)
    val current = titles[index]
    val label = heroPlayLabel(current)
    val context = LocalContext.current
    // The system's "remove animations" setting: titles then change without a fade
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val fade = if (still) snap<Float>() else tween(200)
    val play = remember { FocusRequester() }
    val info = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun step(delta: Int) {
        val next = stepIndex(index, delta, titles.size)
        memory.heroIndex = next
        memory.heroId = titles[next].id
        // The play button leaves with a title that has nothing to play: its focus goes to "Plus d'infos"
        if (titles[next].playId == null) {
            scope.launch {
                withFrameNanos {}
                runCatching { info.requestFocus() }
            }
        }
    }

    fun Modifier.edges(left: Boolean, right: Boolean) = onPreviewKeyEvent {
        if (titles.size < 2 || it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val delta = when {
            left && it.key == Key.DirectionLeft -> -1
            right && it.key == Key.DirectionRight -> 1
            else -> return@onPreviewKeyEvent false
        }
        step(delta)
        true
    }

    // The whole screen, bar included, but a strip of the first shelf's cards
    val height = LocalConfiguration.current.screenHeightDp.dp - HERO_PEEK
    Box(Modifier.fillMaxWidth().height(height)) {
        Crossfade(targetState = current, animationSpec = fade, label = "hero-backdrop") { title ->
            AsyncImage(
                model = client.imageUrl(title.backdrop.itemId, "Backdrop", title.backdrop.tag, 1920).toString(),
                imageLoader = images,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Text stays readable on the left
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Palette.background.copy(alpha = 0.88f),
                    0.34f to Palette.background.copy(alpha = 0.6f),
                    0.62f to Color.Transparent,
                ),
            ),
        )
        // The bar stays readable at the top; the bottom melts into the shelves
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Palette.background.copy(alpha = 0.55f),
                    0.2f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Palette.background,
                ),
            ),
        )
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth()) { header() }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 40.dp).width(430.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Crossfade(targetState = current, animationSpec = fade, label = "hero-text") { title ->
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    TitleMark(title, client, images)
                    heroFacts(title).takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    title.overview?.let {
                        Text(it, color = Palette.textSecondary, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    heroRemaining(title)?.let { (fraction, left) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(130.dp).height(3.dp).background(Color(0x40FFFFFF), CircleShape)) {
                                Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Palette.text, CircleShape))
                            }
                            Text(left, color = Palette.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            // Outside the fade: the focused button stays the same node from one title to the next
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    val target = if (focus != null && focusOn == HeroButton.Play) Modifier.focusRequester(focus) else Modifier
                    HeroAction(
                        label,
                        wide = true,
                        modifier = target.focusRequester(main).focusRequester(play)
                            .focusProperties { this.up = up }.edges(left = true, right = false),
                    ) { onPlay(current) }
                }
                val target = if (focus != null && (focusOn == HeroButton.Info || label == null)) Modifier.focusRequester(focus) else Modifier
                val first = if (label == null) Modifier.focusRequester(main) else Modifier
                HeroAction(
                    "Plus d'infos",
                    wide = false,
                    modifier = target.then(first).focusRequester(info)
                        .focusProperties { this.up = up }.edges(left = label == null, right = true),
                ) { onInfo(current) }
            }
        }
        if (titles.size > 1) Dots(index, titles.size, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 58.dp))
    }
}

/** The title's logo from Emby, else its name; a logo that fails to load gives way to the name. */
@Composable
private fun TitleMark(title: HeroTitle, client: EmbyClient, images: ImageLoader) {
    var failed by remember(title.id) { mutableStateOf(false) }
    val logo = title.logo
    if (logo != null && !failed) {
        AsyncImage(
            model = client.imageUrl(logo.itemId, "Logo", logo.tag, 640).toString(),
            imageLoader = images,
            contentDescription = title.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.width(320.dp).heightIn(max = 110.dp),
        )
    } else {
        Text(title.name, style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A hero button: translucent at rest; white with dark text, slightly larger and ringed in
 * the accent when focused. The [wide] one plays and starts with a triangle.
 */
@Composable
private fun HeroAction(text: String, wide: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp).then(if (wide) Modifier.widthIn(min = 220.dp) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x5C787880),
            contentColor = Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Color.Black,
            pressedContainerColor = Palette.text,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), inset = (-3).dp, shape = shape)),
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (wide) Icon(PlayIcon, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(text, fontSize = 14.sp, fontWeight = if (wide) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Where the shown title sits among the others, the current one longer; the remote changes it. */
@Composable
private fun Dots(index: Int, size: Int, modifier: Modifier) {
    Row(
        modifier.semantics { contentDescription = "Titre ${index + 1} sur $size" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(size) { i ->
            val active = i == index
            Box(
                Modifier.width(if (active) 14.dp else 4.dp).height(4.dp)
                    .background(if (active) Palette.text else Palette.textSecondary.copy(alpha = 0.4f), CircleShape),
            )
        }
    }
}
