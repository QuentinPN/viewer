package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException

/**
 * A film: backdrop, year, length, overview, "Reprendre" / "Lire depuis le début", its version
 * if it has several, and below the screen the « Distribution » (ADR 0068) and « Titres
 * similaires » (ADR 0063) rows.
 */
@Composable
fun MovieScreen(
    client: EmbyClient,
    userId: String,
    movieId: String,
    images: ImageLoader,
    versions: VersionStore,
    /** [mediaSourceId] is the version chosen here; null lets the server pick, as for a film with one version. */
    onPlay: (itemId: String, startTicks: Long, mediaSourceId: String?) -> Unit,
    /** OK on a similar title opens its film or series page. */
    onOpenSimilar: (BaseItemDto) -> Unit,
    /** OK on a « Distribution » card opens that person's page (ADR 0068). */
    onOpenPerson: (CastMember) -> Unit,
    onSessionExpired: () -> Unit,
    /** The card a person page was opened from: focused instead of the play button. */
    focusPerson: String? = null,
    /** Changes after each playback: the resume point and watched mark are read again. */
    refresh: Int = 0,
) {
    var movie by remember { mutableStateOf<Load<BaseItemDto>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt, refresh) {
        // After a playback the film is read again in place; a failure keeps it shown
        val shown = movie is Load.Ready
        if (!shown) movie = Load.Loading
        val next = try {
            // Without Fields, as Windows reads its detail: also carries the MediaSources (ADR 0058)
            Load.Ready(client.fullItem(userId, movieId))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            Load.Failed(e.error.message)
        }
        if (!shown || next is Load.Ready) movie = next
    }
    val similar = rememberSimilar(client, userId, movieId, ready = movie is Load.Ready, onSessionExpired)

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        when (val m = movie) {
            Load.Loading -> Centered("Chargement…")
            is Load.Failed -> Retry(m.message) { attempt++ }
            is Load.Ready -> {
                Backdrop(m.value, client, images)
                // The details fill the screen as before; Down from the buttons scrolls to the row
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val screen = maxHeight
                    val scroll = rememberScrollState()
                    ScrollingPage(scroll, Modifier.fillMaxSize()) {
                        // Back on the buttons, the film's title shows again
                        Details(m.value, versions, onPlay, focusPerson == null, Modifier.height(screen).topOnFocus(scroll))
                        val cast = remember(m.value.people) { castMembers(m.value) }
                        CastRow(cast, client, images, focusPerson, onOpenPerson)
                        SimilarRow(similar, client, images, onOpenSimilar)
                    }
                }
            }
        }
    }
}

@Composable
private fun Details(
    movie: BaseItemDto,
    versions: VersionStore,
    onPlay: (itemId: String, startTicks: Long, mediaSourceId: String?) -> Unit,
    /** False when the « Distribution » row takes the first focus. */
    takeFocus: Boolean,
    modifier: Modifier,
) {
    val played = movie.userData?.played == true
    val position = movie.userData?.playbackPositionTicks ?: 0
    val resume = !played && position > 0
    val first = remember { FocusRequester() }
    // Empty with fewer than two versions: no button, and nothing sent to the server (ADR 0058)
    val choices = remember(movie.mediaSources) { versionChoices(movie.mediaSources) }
    var version by remember(movie.id, choices) { mutableStateOf(versions.load(movie.id, choices)) }
    Column(
        modifier.fillMaxWidth(0.6f).padding(horizontal = 48.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
    ) {
        Text(movie.name, style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            movie.productionYear?.let { Text(it.toString(), color = Palette.textSecondary) }
            movie.runTimeTicks?.let { Text(length(it), color = Palette.textSecondary) }
            tags(movie).forEach { Text(it, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (played) {
                Text(
                    "Vu",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.background(Palette.text, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        movie.overview?.let {
            Text(it, color = Palette.textSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Facts(movie)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // One first button whatever the resume state, so a reload does not drop its focus
            Button(onClick = { onPlay(movie.id, if (resume) position else 0, version) }, modifier = Modifier.focusRequester(first)) {
                Text(if (resume) "Reprendre à ${clock(position)}" else "Lire")
            }
            if (resume) OutlinedButton(onClick = { onPlay(movie.id, 0, version) }) { Text("Lire depuis le début") }
        }
        choices.firstOrNull { it.id == version }?.let { chosen ->
            // OK goes to the next version, as the choices of Réglages
            OutlinedButton(
                onClick = {
                    nextVersion(version, choices)?.let {
                        version = it
                        versions.save(movie.id, choices, it)
                    }
                },
            ) {
                Text("Version : ${chosen.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    // Also after a reload removed the focused "Lire depuis le début"; one frame later, once laid out
    var skip by remember { mutableStateOf(!takeFocus) }
    LaunchedEffect(resume) {
        // Only the first time: a reload that removes "Lire depuis le début" still moves the focus
        if (skip) {
            skip = false
            return@LaunchedEffect
        }
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
}

private const val TICKS_PER_SECOND = 10_000_000L

/** "1 h 52" or "48 min". */
internal fun length(ticks: Long): String {
    val minutes = ticks / (TICKS_PER_SECOND * 60)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${two(minutes % 60)}"
}

/** "1:02:15" or "46:07". */
internal fun clock(ticks: Long): String {
    val seconds = ticks / TICKS_PER_SECOND
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

private fun two(n: Long) = n.toString().padStart(2, '0')
