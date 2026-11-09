package io.github.quentinpn.viewer.tv.ui

import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.HeroTitle
import kotlinx.coroutines.launch

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

/**
 * Home hero (ADR 0059, from Windows ADR 0015): one title at a time over its backdrop.
 * Titles change only on request: Left on the first button shows the previous one,
 * Right on the last button the next one, wrapping around; no automatic rotation.
 * [focus] goes on [focusOn] when the home puts its focus on the hero.
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

    Box(Modifier.fillMaxWidth().height(320.dp)) {
        Crossfade(targetState = current, animationSpec = fade, label = "hero-backdrop") { title ->
            AsyncImage(
                model = client.imageUrl(title.backdrop.itemId, "Backdrop", title.backdrop.tag, 1280).toString(),
                imageLoader = images,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Text stays readable on the left; the bottom melts into the shelves
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Palette.background.copy(alpha = 0.9f), 0.55f to Palette.background.copy(alpha = 0.45f), 1f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.background)))
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.6f).padding(start = 48.dp, end = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Crossfade(targetState = current, animationSpec = fade, label = "hero-text") { title ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (title.resumed) Text("Reprendre", color = Palette.accent, style = MaterialTheme.typography.labelLarge)
                    Text(title.name, style = MaterialTheme.typography.headlineLarge, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    heroFacts(title).takeIf { it.isNotEmpty() }?.let {
                        Text(it, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    title.overview?.let {
                        Text(it, color = Palette.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            // Outside the fade: the focused button stays the same node from one title to the next
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    val target = if (focus != null && focusOn == HeroButton.Play) Modifier.focusRequester(focus) else Modifier
                    Button(
                        onClick = { onPlay(current) },
                        modifier = target.focusRequester(play).edges(left = true, right = false),
                    ) { Text(label) }
                }
                val target = if (focus != null && (focusOn == HeroButton.Info || label == null)) Modifier.focusRequester(focus) else Modifier
                OutlinedButton(
                    onClick = { onInfo(current) },
                    modifier = target.focusRequester(info).edges(left = label == null, right = true),
                ) { Text("Plus d'infos") }
                if (titles.size > 1) Dots(index, titles.size)
            }
        }
    }
}

/** Where the shown title sits among the others; the remote changes it, not these. */
@Composable
private fun Dots(index: Int, size: Int) {
    Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("◀", color = Palette.textSecondary, style = MaterialTheme.typography.labelSmall)
        repeat(size) { i ->
            Box(Modifier.size(if (i == index) 10.dp else 8.dp).background(if (i == index) Palette.text else Palette.textSecondary.copy(alpha = 0.4f), CircleShape))
        }
        Text("▶", color = Palette.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text("${index + 1} sur $size", color = Palette.textSecondary, style = MaterialTheme.typography.labelMedium)
    }
}
