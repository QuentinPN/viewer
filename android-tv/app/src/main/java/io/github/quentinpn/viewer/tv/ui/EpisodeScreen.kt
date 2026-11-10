package io.github.quentinpn.viewer.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Episode page (ADR 0060), ported from the Windows episode detail (ItemDetail.svelte,
// format.ts, session.rs `neighbours`, ADR 0016).

/** The episodes just before and after one, across seasons. */
internal data class Neighbours(val previous: BaseItemDto?, val next: BaseItemDto?)

/**
 * Neighbours of [episodeId] in an `AdjacentTo` answer, other series left out, as
 * [io.github.quentinpn.viewer.tv.emby.nextEpisode]. None when the episode is not in it.
 */
internal fun neighbours(episodes: List<BaseItemDto>, episodeId: String, seriesId: String): Neighbours {
    val series = episodes.filter { it.seriesId == null || it.seriesId == seriesId }
    val at = series.indexOfFirst { it.id == episodeId }
    if (at < 0) return Neighbours(null, null)
    return Neighbours(series.getOrNull(at - 1), series.getOrNull(at + 1))
}

/** `Saison 2 · Épisode 14`, `Saison 1 · Épisodes 2–3`, `Spécial 3` (Windows `episodePosition`). */
internal fun episodePosition(season: Int?, index: Int?, indexEnd: Int?): String? {
    if (season == 0) return if (index == null) "Spécial" else "Spécial $index"
    val episode = when {
        index == null -> null
        indexEnd != null && indexEnd > index -> "Épisodes $index–$indexEnd"
        else -> "Épisode $index"
    }
    return listOfNotNull(season?.let { "Saison $it" }, episode).joinToString(" · ").ifEmpty { null }
}

/** `S2 · É1`, `S1 · É2–3`, `Spécial 3` on the previous / next buttons (Windows `episodeLinkCode`). */
internal fun episodeCode(season: Int?, index: Int?, indexEnd: Int?): String? {
    if (season == 0) return if (index == null) "Spécial" else "Spécial $index"
    val episode = when {
        index == null -> null
        indexEnd != null && indexEnd > index -> "É$index–$indexEnd"
        else -> "É$index"
    }
    return listOfNotNull(season?.let { "S$it" }, episode).joinToString(" · ").ifEmpty { null }
}

/** What a previous / next button shows under its direction: `S1 · É3 · Le titre`. */
internal fun neighbourLabel(episode: BaseItemDto): String =
    listOfNotNull(
        episodeCode(episode.parentIndexNumber, episode.indexNumber, episode.indexNumberEnd),
        episode.name.trim().takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

private val LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRANCE)

/**
 * `5 juillet 2024`: the air date in the device's time zone, as Windows formats it.
 * Emby sends UTC (`2024-07-04T22:00:00.0000000Z`); a date without zone is taken as is.
 */
internal fun airDate(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
    val text = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val date = runCatching { Instant.parse(text).atZone(zone).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
        ?: return null
    return LONG_DATE.format(date)
}

/** One image to draw: item, image type and tag. */
internal data class Artwork(val itemId: String, val type: String, val tag: String)

/** The episode's own image, else the series backdrop (ADR 0016). */
internal fun episodeArtwork(episode: BaseItemDto): Artwork? =
    episode.imageTags["Primary"]?.let { Artwork(episode.id, "Primary", it) }
        ?: episode.parentBackdropItemId?.let { id ->
            episode.parentBackdropImageTags.firstOrNull()?.let { Artwork(id, "Backdrop", it) }
        }

/** The page opened for [episode] from a previous / next button, its season named for Back. */
internal fun neighbourTarget(seriesId: String, episode: BaseItemDto): SeriesTarget =
    SeriesTarget(seriesId, seasonId = episode.seasonId, seasonNumber = episode.parentIndexNumber, episodeId = episode.id)

/**
 * The series page Back returns to: [target]'s season (the one its card was in), else the
 * loaded episode's own, with the episode's card focused (ADR 0054).
 */
internal fun returnTarget(target: SeriesTarget, episode: BaseItemDto?): SeriesTarget = target.copy(
    seasonId = target.seasonId ?: episode?.seasonId,
    seasonNumber = target.seasonNumber ?: episode?.parentIndexNumber,
)

private class EpisodeData(val episode: BaseItemDto, val neighbours: Neighbours)

private enum class Focus { Play, Previous, Next }

/** A pending focus move; a new token asks again for the same button. */
private data class FocusAsk(val to: Focus, val token: Int)

/**
 * One episode: series, season and number, title, image, air date, length, overview,
 * credits and guest stars (ADR 0068), "Lire" / "Reprendre" / "Lire depuis le début", its version if it has several,
 * and the episodes before and after it across seasons.
 */
@Composable
fun EpisodeScreen(
    client: EmbyClient,
    userId: String,
    /** The episode, and the season of the series page it was opened from. */
    target: SeriesTarget,
    images: ImageLoader,
    versions: VersionStore,
    /** [mediaSourceId] is the version chosen here; null lets the server pick. */
    onPlay: (itemId: String, startTicks: Long, mediaSourceId: String?) -> Unit,
    /** Previous / next: the page shown becomes that episode's. */
    onNavigate: (SeriesTarget) -> Unit,
    /** Back: the series page to show, on this episode's season and card. */
    onBack: (SeriesTarget) -> Unit,
    /** OK on a « Distribution » card opens that person's page (ADR 0068). */
    onOpenPerson: (CastMember) -> Unit,
    onSessionExpired: () -> Unit,
    /** Changes after each playback: the resume point and watched mark are read again. */
    refresh: Int = 0,
    /** The card a person page was opened from: focused instead of the play button. */
    focusPerson: String? = null,
) {
    val episodeId = target.episodeId ?: return
    var data by remember { mutableStateOf<Load<EpisodeData>>(Load.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    // Button used to reach this episode; its focus is kept once the new episode is shown
    var moved by remember { mutableStateOf<Focus?>(null) }
    var ask by remember { mutableStateOf<FocusAsk?>(null) }

    BackHandler { onBack(returnTarget(target, (data as? Load.Ready)?.value?.episode)) }

    LaunchedEffect(episodeId, attempt, refresh) {
        // The page shown stays while the next one loads, or when a reload after a playback fails
        val before = (data as? Load.Ready)?.value
        if (before == null) data = Load.Loading
        val next = try {
            // Without Fields, as the film page: also carries MediaSources and People (ADR 0058)
            val episode = client.fullItem(userId, episodeId)
            val around = episode.seriesId?.let { seriesId ->
                try {
                    neighbours(client.adjacentEpisodes(userId, seriesId, episodeId), episodeId, seriesId)
                } catch (e: EmbyException) {
                    // Without neighbours the page still opens
                    if (e.error == EmbyError.SessionExpired) throw e
                    null
                }
            }
            Load.Ready(EpisodeData(episode, around ?: Neighbours(null, null)))
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            Load.Failed(e.error.message)
        }
        if (before == null || next is Load.Ready || before.episode.id != episodeId) data = next
        val shown = (data as? Load.Ready)?.value ?: return@LaunchedEffect
        val token = (ask?.token ?: 0) + 1
        val wasResume = before?.let { resumes(it.episode) }
        ask = when {
            // Back from a person page: the « Distribution » row focuses its card itself
            before == null -> if (focusPerson != null) ask else FocusAsk(Focus.Play, token)
            before.episode.id != shown.episode.id -> FocusAsk(movedFocus(moved, shown.neighbours), token)
            // A reload removed the focused "Lire depuis le début": back on the first button
            wasResume != resumes(shown.episode) -> FocusAsk(Focus.Play, token)
            else -> ask
        }
        moved = null
    }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        when (val d = data) {
            Load.Loading -> Centered("Chargement…")
            is Load.Failed -> Retry(d.message) { attempt++ }
            is Load.Ready -> {
                val episode = d.value.episode
                EpisodeArtwork(episode, client, images)
                // The details fill the screen as before; Down from the buttons scrolls to the guest stars
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val screen = maxHeight
                    val scroll = rememberScrollState()
                    ScrollingPage(scroll, Modifier.fillMaxSize()) {
                        Details(
                            d.value,
                            versions,
                            ask,
                            onPlay,
                            onNavigate = { to, direction ->
                                moved = direction
                                onNavigate(neighbourTarget(episode.seriesId ?: target.seriesId, to))
                            },
                            // Back on the buttons, the episode's title shows again
                            Modifier.height(screen).topOnFocus(scroll),
                        )
                        val cast = remember(episode.people) { castMembers(episode) }
                        CastRow(cast, client, images, focusPerson, onOpenPerson)
                    }
                }
            }
        }
    }
}

private fun resumes(episode: BaseItemDto): Boolean =
    episode.userData?.played != true && (episode.userData?.playbackPositionTicks ?: 0) > 0

/** The button used if the new episode has it too, else the other neighbour, else the play button. */
private fun movedFocus(used: Focus?, around: Neighbours): Focus {
    val has = { f: Focus -> if (f == Focus.Previous) around.previous != null else around.next != null }
    return when (used) {
        Focus.Previous, Focus.Next -> {
            val other = if (used == Focus.Previous) Focus.Next else Focus.Previous
            when {
                has(used) -> used
                has(other) -> other
                else -> Focus.Play
            }
        }
        else -> Focus.Play
    }
}

/** The episode's image, else the series backdrop, faded into the black canvas. */
@Composable
private fun EpisodeArtwork(episode: BaseItemDto, client: EmbyClient, images: ImageLoader) {
    val art = episodeArtwork(episode) ?: return
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AsyncImage(
            model = client.imageUrl(art.itemId, art.type, art.tag, 1280).toString(),
            imageLoader = images,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.55f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black),
            ),
        )
    }
}

@Composable
private fun Details(
    data: EpisodeData,
    versions: VersionStore,
    ask: FocusAsk?,
    onPlay: (itemId: String, startTicks: Long, mediaSourceId: String?) -> Unit,
    onNavigate: (BaseItemDto, Focus) -> Unit,
    modifier: Modifier,
) {
    val episode = data.episode
    val played = episode.userData?.played == true
    val position = episode.userData?.playbackPositionTicks ?: 0
    val resume = !played && position > 0
    val play = remember { FocusRequester() }
    val previous = remember { FocusRequester() }
    val next = remember { FocusRequester() }
    // Empty with fewer than two versions: no button, and nothing sent to the server (ADR 0058)
    val choices = remember(episode.mediaSources) { versionChoices(episode.mediaSources) }
    var version by remember(episode.id, choices) { mutableStateOf(versions.load(episode.id, choices)) }
    Column(
        modifier.fillMaxWidth(0.65f).padding(horizontal = 48.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        val heading = listOfNotNull(
            episode.seriesName?.trim()?.takeIf { it.isNotEmpty() },
            episodePosition(episode.parentIndexNumber, episode.indexNumber, episode.indexNumberEnd),
        )
        if (heading.isNotEmpty()) {
            Text(heading.joinToString("  ·  "), color = Palette.textSecondary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(episode.name, style = MaterialTheme.typography.headlineLarge, color = Palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            airDate(episode.premiereDate)?.let { Text(it, color = Palette.textSecondary) }
            episode.runTimeTicks?.let { Text(length(it), color = Palette.textSecondary) }
            // No genres here: an episode rarely has its own (ADR 0074)
            RatingBadge(episode)
            scores(episode).forEach { Text(it, color = Palette.textSecondary, maxLines = 1) }
            if (played) {
                Text(
                    "Vu",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.background(Palette.text, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        episode.overview?.let {
            Text(it, color = Palette.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Facts(episode, limit = 3)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // One first button whatever the resume state, so a reload does not drop its focus
            Button(onClick = { onPlay(episode.id, if (resume) position else 0, version) }, modifier = Modifier.focusRequester(play)) {
                Text(if (resume) "Reprendre à ${clock(position)}" else "Lire")
            }
            if (resume) OutlinedButton(onClick = { onPlay(episode.id, 0, version) }) { Text("Lire depuis le début") }
        }
        choices.firstOrNull { it.id == version }?.let { chosen ->
            // OK goes to the next version, as on the film page
            OutlinedButton(
                onClick = {
                    nextVersion(version, choices)?.let {
                        version = it
                        versions.save(episode.id, choices, it)
                    }
                },
            ) {
                Text("Version : ${chosen.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val around = data.neighbours
        if (around.previous != null || around.next != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                around.previous?.let { NeighbourButton("Épisode précédent", it, previous) { onNavigate(it, Focus.Previous) } }
                around.next?.let { NeighbourButton("Épisode suivant", it, next) { onNavigate(it, Focus.Next) } }
            }
        }
    }
    // After the first load, a move to another episode or a reload; one frame later, once laid out
    LaunchedEffect(ask) {
        val to = ask?.to ?: return@LaunchedEffect
        withFrameNanos {}
        val requester = when (to) {
            Focus.Play -> play
            Focus.Previous -> previous
            Focus.Next -> next
        }
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun NeighbourButton(direction: String, episode: BaseItemDto, requester: FocusRequester, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.widthIn(max = 300.dp).focusRequester(requester)) {
        Column {
            Text(direction, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(neighbourLabel(episode), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
