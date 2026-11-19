package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.Centered
import io.github.quentinpn.viewer.tv.ui.Load
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.Retry
import io.github.quentinpn.viewer.tv.request.Capabilities
import io.github.quentinpn.viewer.tv.request.MediaDetails
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.StatusLabel
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.mediaStatusLabel
import kotlinx.coroutines.delay

/** A level over the Demander segments; Back pops it. */
internal sealed interface RequestsPage {
    data class Media(val type: MediaType, val tmdbId: Int) : RequestsPage
}

private const val CAST_SHOWN = 10

private const val MESSAGE_MS = 4_000L

/**
 * A title of Request: backdrop under a left and bottom scrim, title, meta, synopsis, then « Lire »
 * when it is in Emby and « Demander » when there is something to ask for (and the account may),
 * else « Rien à demander »; the cast below. A blocked title cannot be asked for. After a request the page
 * reads the title again and says so for a moment.
 */
@Composable
internal fun MediaTv(
    repository: RequestRepository,
    memory: RequestsMemory,
    art: RequestArt,
    page: RequestsPage.Media,
    capabilities: Capabilities,
    /** OK on « Lire »: the title's Emby page, by its Emby id. */
    onPlay: (MediaDetails) -> Unit,
) {
    var details by remember(page) { mutableStateOf<Load<MediaDetails>>(Load.Loading) }
    var attempt by remember(page) { mutableIntStateOf(0) }
    var asking by remember(page) { mutableStateOf(false) }
    var message by remember(page) { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(page, attempt) {
        // A reload after a request keeps the page shown; a failure then leaves it as it was
        val shown = details is Load.Ready
        if (!shown) details = Load.Loading
        val next: Load<MediaDetails> = tried { repository.media(page.type, page.tmdbId) }.fold({ Load.Ready(it) }, { Load.Failed(it.requestMessage()) })
        if (!shown || next is Load.Ready) details = next
    }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(MESSAGE_MS)
        message = null
    }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        when (val d = details) {
            Load.Loading -> Centered("Chargement…")
            is Load.Failed -> Retry(d.message) { attempt++ }
            is Load.Ready -> {
                val title = d.value
                Backdrop(title, art)
                val asked = capabilities.canRequestFor(title.type)
                val something = title.blocked == null && isRequestable(title)
                val canAsk = asked && something
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
                ) {
                    Column(Modifier.fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title.title, style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Meta(title)
                        if (title.overview.isNotBlank()) {
                            Text(title.overview, color = Palette.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        SeasonPills(title)
                        title.blocked?.let { Text("Ce titre est bloqué : il ne peut pas être demandé.", color = Palette.warning) }
                    }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val read = title.embyItemId != null
                        if (read) Button(onClick = { onPlay(title) }, modifier = Modifier.focusRequester(first)) { Text("Lire") }
                        if (canAsk) {
                            Button(onClick = { asking = true }, modifier = if (read) Modifier else Modifier.focusRequester(first)) { Text("Demander") }
                        } else {
                            Text(
                                if (something && !asked) "Les demandes ne sont pas autorisées pour ton compte" else "Rien à demander",
                                color = Palette.textSecondary,
                            )
                        }
                    }
                    message?.let { Text(it, color = Palette.success) }
                    CastRowTv(title, art)
                }
                // The first button takes the focus once the page is read
                FocusOnOpen(first)
                if (asking) {
                    RequestDialogTv(
                        repository,
                        title,
                        onRequested = {
                            asking = false
                            message = "Demande envoyée"
                            memory.revision++
                            attempt++
                        },
                        onDismiss = { asking = false },
                    )
                }
            }
        }
    }
}

private fun Capabilities.canRequestFor(type: MediaType) = if (type == MediaType.Movie) canRequestMovies else canRequestTv

/** The backdrop, a scrim on its left for the text and one at the bottom for the cast, all into the black canvas. */
@Composable
private fun Backdrop(title: MediaDetails, art: RequestArt) {
    Box(Modifier.fillMaxSize()) {
        RequestImage(art, title.backdropPath ?: title.posterPath, "w1280", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.55f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black)))
    }
}

/** « 2021 · 42 min · Animation, Science-Fiction », with the title's status. */
@Composable
private fun Meta(title: MediaDetails) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        title.year?.let { Text(it.toString(), color = Palette.textSecondary) }
        val size = when (title.type) {
            MediaType.Movie -> runtimeLabel(title.runtime)
            MediaType.Tv -> title.seasons.count { it.seasonNumber > 0 }.takeIf { it > 0 }?.let { if (it == 1) "1 saison" else "$it saisons" }
        }
        size?.let { Text(it, color = Palette.textSecondary) }
        if (title.genres.isNotEmpty()) {
            Text(
                title.genres.joinToString(", ") { it.name },
                color = Palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        title.status?.let { StatusPill(mediaStatusLabel(it, title.progress)) }
    }
}

/** « S2 · Partiel 60 % » for each season that has a status: read-only, a series' state at a glance. */
@Composable
private fun SeasonPills(title: MediaDetails) {
    val seasons = title.seasons.filter { it.seasonNumber > 0 && it.status != null }
    if (seasons.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (season in seasons.take(SEASON_PILLS)) {
            val label = mediaStatusLabel(checkNotNull(season.status), season.progress)
            StatusPill(StatusLabel("S${season.seasonNumber} · ${label.text}", label.tone))
        }
    }
}

private const val SEASON_PILLS = 6

/** The first names of the cast, with their photos when Request has them; read-only. */
@Composable
private fun CastRowTv(title: MediaDetails, art: RequestArt) {
    if (title.cast.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(title.cast.take(CAST_SHOWN), key = { it.id }) { person ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.surfaceRaised)) {
                    RequestImage(art, person.profilePath, "w185", Modifier.fillMaxSize())
                }
                Text(person.name, color = Palette.text, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
