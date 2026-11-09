package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException

internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Ready<T>(val value: T) : Load<T>

    data class Failed(val message: String) : Load<Nothing>
}

private class SeriesData(val series: BaseItemDto, val seasons: List<BaseItemDto>)

/** A series: its overview, a chip per season, and the season's episodes in a row. */
@Composable
fun SeriesScreen(
    client: EmbyClient,
    userId: String,
    seriesId: String,
    images: ImageLoader,
    onPlay: (itemId: String, startTicks: Long) -> Unit,
    onSessionExpired: () -> Unit,
    /** Changes after each playback: the episodes' watched state is read again. */
    refresh: Int = 0,
) {
    var data by remember { mutableStateOf<Load<SeriesData>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var seasonId by remember { mutableStateOf<String?>(null) }

    suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Ready(block())
    } catch (e: EmbyException) {
        if (e.error == EmbyError.SessionExpired) onSessionExpired()
        Load.Failed(e.error.message)
    }

    LaunchedEffect(attempt, refresh) {
        // After a playback the seasons' counts are read again in place, the season kept
        val shown = data is Load.Ready
        if (!shown) data = Load.Loading
        val next = load { SeriesData(client.item(userId, seriesId), client.seasons(userId, seriesId).items) }
        if (!shown || next is Load.Ready) data = next
        val seasons = (data as? Load.Ready)?.value?.seasons.orEmpty()
        if (seasonId == null) seasonId = defaultSeason(seasons)?.id
    }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        when (val d = data) {
            Load.Loading -> Centered("Chargement…")
            is Load.Failed -> Retry(d.message) { attempt++ }
            is Load.Ready -> {
                Backdrop(d.value.series, client, images)
                Column(Modifier.fillMaxSize().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Header(d.value.series)
                    if (d.value.seasons.isEmpty()) {
                        Text("Aucune saison sur le serveur.", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 48.dp))
                    } else {
                        SeasonChips(d.value.seasons, seasonId) { seasonId = it }
                        seasonId?.let { id ->
                            Episodes(client, userId, seriesId, id, d.value.series, images, onPlay, onSessionExpired, refresh)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Palette.textSecondary) }
}

@Composable
internal fun Retry(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, color = Palette.error)
        Button(onClick = onRetry) { Text("Réessayer") }
    }
}

/** The series backdrop, faded into the black canvas so the text stays readable. */
@Composable
internal fun Backdrop(series: BaseItemDto, client: EmbyClient, images: ImageLoader) {
    val tag = series.backdropImageTags.firstOrNull() ?: return
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AsyncImage(
            model = client.imageUrl(series.id, "Backdrop", tag, 1280).toString(),
            imageLoader = images,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.6f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black),
            ),
        )
    }
}

@Composable
private fun Header(series: BaseItemDto) {
    Column(Modifier.padding(horizontal = 48.dp).fillMaxWidth(0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(series.name, style = MaterialTheme.typography.displaySmall, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        series.productionYear?.let { Text(it.toString(), color = Palette.textSecondary) }
        series.overview?.let {
            Text(it, color = Palette.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SeasonChips(seasons: List<BaseItemDto>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(seasons, key = { it.id }) { season ->
            // Filled for the season shown, outlined for the others (FilterChip is still experimental)
            if (season.id == selected) {
                Button(onClick = {}) { SeasonText(season) }
            } else {
                OutlinedButton(onClick = { onSelect(season.id) }) { SeasonText(season) }
            }
        }
    }
}

/** The season's name, then a check once watched or the episodes left in the accent colour, as on Windows. */
@Composable
private fun SeasonText(season: BaseItemDto) {
    Text(seasonLabel(season))
    val data = season.userData
    val unplayed = data?.unplayedItemCount ?: 0
    when {
        data?.played == true -> Text("  ✓")
        unplayed > 0 -> Text("  $unplayed", color = Palette.accent)
    }
}

/** First regular season with episodes left, else the first regular one, else the specials (Windows `defaultSeason`). */
internal fun defaultSeason(seasons: List<BaseItemDto>): BaseItemDto? {
    val regular = seasons.filter { it.indexNumber != 0 }
    return regular.firstOrNull { it.userData?.played != true && (it.userData?.unplayedItemCount ?: 0) > 0 }
        ?: regular.firstOrNull()
        ?: seasons.firstOrNull()
}

private fun seasonLabel(season: BaseItemDto) = when (season.indexNumber) {
    0 -> "Épisodes spéciaux"
    null -> season.name
    else -> "Saison ${season.indexNumber}"
}

@Composable
private fun Episodes(
    client: EmbyClient,
    userId: String,
    seriesId: String,
    seasonId: String,
    series: BaseItemDto,
    images: ImageLoader,
    onPlay: (itemId: String, startTicks: Long) -> Unit,
    onSessionExpired: () -> Unit,
    refresh: Int,
) {
    var episodes by remember(seasonId) { mutableStateOf<Load<List<BaseItemDto>>>(Load.Loading) }
    var attempt by remember(seasonId) { mutableIntStateOf(0) }
    var focused by remember(seasonId) { mutableStateOf<BaseItemDto?>(null) }

    LaunchedEffect(seasonId, attempt, refresh) {
        // After a playback the episodes are read again in place, focus kept
        val shown = episodes is Load.Ready
        if (!shown) episodes = Load.Loading
        val next = try {
            Load.Ready(client.episodes(userId, seriesId, seasonId))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            Load.Failed(e.error.message)
        }
        if (!shown || next is Load.Ready) episodes = next
        focused = (next as? Load.Ready)?.value?.find { it.id == focused?.id } ?: focused
    }

    when (val e = episodes) {
        Load.Loading -> Text("Chargement des épisodes…", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 48.dp))
        is Load.Failed -> Retry(e.message) { attempt++ }
        is Load.Ready -> if (e.value.isEmpty()) {
            Text("Aucun épisode dans cette saison.", color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 48.dp))
        } else {
            // Focus lands on the first episode not watched yet
            val start = e.value.indexOfFirst { it.userData?.played != true }.coerceAtLeast(0)
            val first = remember(seasonId) { FocusRequester() }
            // Starts scrolled to that episode: an item off screen cannot take focus
            val row = remember(seasonId) { LazyListState(firstVisibleItemIndex = start) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(
                    state = row,
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(e.value, key = { _, it -> it.id }) { index, episode ->
                        val modifier = if (index == start) Modifier.focusRequester(first) else Modifier
                        EpisodeCard(episode, series, client, images, modifier.onFocusChanged { if (it.isFocused) focused = episode }) {
                            onPlay(episode.id, if (episode.userData?.played == true) 0 else episode.userData?.playbackPositionTicks ?: 0)
                        }
                    }
                }
                focused?.overview?.let {
                    Text(
                        it,
                        color = Palette.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth(0.7f),
                    )
                }
            }
            LaunchedEffect(seasonId) {
                runCatching { first.requestFocus() }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: BaseItemDto,
    series: BaseItemDto,
    client: EmbyClient,
    images: ImageLoader,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.accent), shape = shape)),
            colors = CardDefaults.colors(containerColor = Palette.surface),
        ) {
            // A Box, not the Card's Column: the progress bar overlays the image
            Box(Modifier.fillMaxSize()) {
                val image = episode.imageTags["Primary"]?.let { client.imageUrl(episode.id, "Primary", it, 560) }
                    ?: series.backdropImageTags.firstOrNull()?.let { client.imageUrl(series.id, "Backdrop", it, 560) }
                if (image != null) {
                    AsyncImage(
                        model = image.toString(),
                        imageLoader = images,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(shape),
                    )
                }
                if (episode.userData?.played == true) {
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopEnd) {
                        Text(
                            "Vu",
                            color = Color.Black,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.background(Palette.text, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    Progress(episode)
                }
            }
        }
        Text(
            listOfNotNull(episode.indexNumber?.let { "É$it" }, episode.name).joinToString(" · "),
            color = Palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        episode.runTimeTicks?.let { ticks ->
            Text("${ticks / 600_000_000} min", color = Palette.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.height(18.dp))
        }
    }
}
