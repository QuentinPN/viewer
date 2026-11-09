package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

private const val RESUME_LIMIT = 12
private const val LATEST_LIMIT = 16

private data class Shelf(val title: String, val items: List<BaseItemDto>, val landscape: Boolean, val resume: Boolean)

private sealed interface HomeState {
    data object Loading : HomeState

    data class Ready(val shelves: List<Shelf>) : HomeState

    data class Failed(val message: String) : HomeState
}

/** "Reprendre", then the latest additions of each film and series library. */
@Composable
fun HomeScreen(
    client: EmbyClient,
    userId: String,
    userName: String,
    images: ImageLoader,
    onPlay: (itemId: String, startTicks: Long) -> Unit,
    onSessionExpired: () -> Unit,
    onSignOut: () -> Unit,
) {
    var state by remember { mutableStateOf<HomeState>(HomeState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        state = HomeState.Loading
        state = try {
            val resume = client.resumeItems(userId, RESUME_LIMIT).items
            val libraries = client.views(userId).items.filter { it.collectionType == "movies" || it.collectionType == "tvshows" }
            val shelves = buildList {
                if (resume.isNotEmpty()) add(Shelf("Reprendre", resume, landscape = true, resume = true))
                for (library in libraries) {
                    val latest = client.latest(userId, library.id, LATEST_LIMIT)
                    if (latest.isNotEmpty()) add(Shelf("Derniers ajouts · ${library.name}", latest, landscape = false, resume = false))
                }
            }
            HomeState.Ready(shelves)
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            HomeState.Failed(e.error.message)
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Accueil", style = MaterialTheme.typography.headlineMedium, color = Palette.text)
            OutlinedButton(onClick = onSignOut) { Text("Déconnecter $userName") }
        }
        notice?.let { Text(it, color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 48.dp)) }
        when (val s = state) {
            HomeState.Loading -> Message("Chargement…")
            is HomeState.Failed -> Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(s.message, color = Palette.error)
                Button(onClick = { attempt++ }) { Text("Réessayer") }
            }
            is HomeState.Ready -> if (s.shelves.isEmpty()) {
                Message("Aucun film ni série dans vos bibliothèques.")
            } else {
                Shelves(s.shelves, client, images) { item, resume ->
                    when (item.type) {
                        "Movie", "Episode" -> {
                            notice = null
                            onPlay(item.id, if (resume) item.userData?.playbackPositionTicks ?: 0 else 0)
                        }
                        else -> notice = "La navigation dans les séries arrive dans une prochaine étape."
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Palette.textSecondary) }
}

@Composable
private fun Shelves(
    shelves: List<Shelf>,
    client: EmbyClient,
    images: ImageLoader,
    onOpen: (BaseItemDto, resume: Boolean) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        items(shelves, key = { it.title }) { shelf ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    shelf.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.text,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(shelf.items, key = { it.id }) { item ->
                        val focus = if (shelf === shelves.first() && item === shelf.items.first()) Modifier.focusRequester(first) else Modifier
                        ItemCard(item, shelf.landscape, client, images, focus) { onOpen(item, shelf.resume) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: BaseItemDto,
    landscape: Boolean,
    client: EmbyClient,
    images: ImageLoader,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val width: Dp = if (landscape) 320.dp else 180.dp
    val ratio = if (landscape) 16f / 9f else 2f / 3f
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(ratio),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.accent), shape = shape)),
            colors = CardDefaults.colors(containerColor = Palette.surface),
        ) {
            val image = imageFor(item, landscape, client, if (landscape) 640 else 360)
            if (image != null) {
                AsyncImage(
                    model = image,
                    imageLoader = images,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(shape),
                )
            } else {
                Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                    Text(item.name, color = Palette.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (landscape) Progress(item)
        }
        Text(title(item), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        subtitle(item)?.let {
            Text(it, color = Palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Watched part of a resumable item, as a thin bar at the bottom of the card. */
@Composable
private fun Progress(item: BaseItemDto) {
    val position = item.userData?.playbackPositionTicks ?: 0
    val total = item.runTimeTicks ?: 0
    if (position <= 0 || total <= 0) return
    val fraction = (position.toFloat() / total).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Palette.text))
        }
    }
}

private fun title(item: BaseItemDto) = if (item.type == "Episode") item.seriesName ?: item.name else item.name

private fun subtitle(item: BaseItemDto): String? = when (item.type) {
    "Episode" -> listOfNotNull(
        item.parentIndexNumber?.let { s -> item.indexNumber?.let { e -> "S$s · É$e" } },
        item.name,
    ).joinToString(" — ")
    else -> item.productionYear?.toString()
}

/** Landscape: the item's own thumb or 16:9 episode still, else a backdrop; portrait: the poster. */
private fun imageFor(item: BaseItemDto, landscape: Boolean, client: EmbyClient, maxWidth: Int): String? {
    fun url(id: String, type: String, tag: String) = client.imageUrl(id, type, tag, maxWidth).toString()
    if (!landscape) return item.imageTags["Primary"]?.let { url(item.id, "Primary", it) }
    item.imageTags["Thumb"]?.let { return url(item.id, "Thumb", it) }
    if (item.type == "Episode") item.imageTags["Primary"]?.let { return url(item.id, "Primary", it) }
    item.backdropImageTags.firstOrNull()?.let { return url(item.id, "Backdrop", it) }
    item.parentThumbItemId?.let { id -> item.parentThumbImageTag?.let { return url(id, "Thumb", it) } }
    item.parentBackdropItemId?.let { id -> item.parentBackdropImageTags.firstOrNull()?.let { return url(id, "Backdrop", it) } }
    return null
}
