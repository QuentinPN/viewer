package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.Centered
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Load
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.Retry
import io.github.quentinpn.viewer.tv.ui.SectionHeader
import io.github.quentinpn.viewer.tv.ui.reducedMotion
import io.github.quentinpn.viewer.tv.request.DiscoverRow
import io.github.quentinpn.viewer.tv.request.MediaStatus
import io.github.quentinpn.viewer.tv.request.MediaSummary
import io.github.quentinpn.viewer.tv.request.RequestStatus
import io.github.quentinpn.viewer.tv.request.RequestView
import io.github.quentinpn.viewer.tv.request.SeeAll
import io.github.quentinpn.viewer.tv.request.RequestException
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.key
import io.github.quentinpn.viewer.tv.request.requestLine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The requests the strip shows: those still on their way. */
internal val IN_FLIGHT = listOf(RequestStatus.Pending, RequestStatus.Approved, RequestStatus.Sent)

private const val STRIP_CARDS = 3

private const val ROW_CARD_WIDTH = 160

/** Success or the member-facing French message of a failed read; only [RequestException] is caught. */
internal suspend fun <T> tried(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: RequestException) {
    Result.failure(e)
}

internal fun Throwable.requestMessage(): String = (this as? RequestException)?.error?.message ?: message.orEmpty()

/**
 * Découvrir (ADR 0125): « Mes demandes en cours » when there are some, then the server's rows with
 * « Tout voir ». The focused card's title and meta show under its row. Read again after a request.
 */
@Composable
internal fun DiscoverTv(
    repository: RequestRepository,
    memory: RequestsMemory,
    art: RequestArt,
    onOpen: (MediaSummary) -> Unit,
    onSeeAll: (SeeAll) -> Unit,
    onOpenRequest: (RequestView) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val restore = remember { FocusRequester() }
    LaunchedEffect(memory.revision, attempt) {
        val stamp = memory.revision to attempt
        if (memory.discoverStamp == stamp) return@LaunchedEffect
        // A reload after a request keeps what shows; a first read or a retry shows the loading state
        if (memory.discover !is Load.Ready) memory.discover = Load.Loading
        coroutineScope {
            val rows = async { tried { repository.discover() } }
            val strip = async { tried { repository.myRequests(IN_FLIGHT) } }
            memory.discover = rows.await().fold({ Load.Ready(it.rows.filter { row -> row.items.isNotEmpty() }) }, { Load.Failed(it.requestMessage()) })
            // The strip is a bonus: without it the rows still show
            val inFlight = strip.await().getOrNull()
            memory.inFlight = inFlight?.results.orEmpty()
            inFlight?.let { memory.mine.totals = it.totals }
        }
        memory.discoverStamp = stamp
    }
    val rows = (memory.discover as? Load.Ready)?.value
    FocusRestore(memory, rows != null, restore)

    when (val d = memory.discover) {
        Load.Loading -> Centered("Chargement…")
        is Load.Failed -> Retry(d.message) { attempt++ }
        is Load.Ready -> {
            val strip = memory.inFlight.take(STRIP_CARDS)
            if (d.value.isEmpty() && strip.isEmpty()) {
                Centered("Rien à découvrir pour le moment.")
                return
            }
            LazyColumn(
                state = memory.discoverColumn,
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (strip.isNotEmpty()) {
                    item(key = "strip") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionHeader("Mes demandes en cours", memory.inFlight.size, Modifier.padding(horizontal = 48.dp))
                            Row(Modifier.padding(horizontal = 48.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                for (request in strip) {
                                    InFlightCard(
                                        request,
                                        art,
                                        Modifier.width(330.dp).then(if (requestKey(request) == memory.opened) Modifier.focusRequester(restore) else Modifier),
                                    ) { onOpenRequest(request) }
                                }
                            }
                        }
                    }
                }
                items(d.value, key = { it.id }) { row ->
                    DiscoverRowTv(row, memory, art, restore, onOpen, onSeeAll)
                }
            }
        }
    }
}

@Composable
private fun DiscoverRowTv(
    row: DiscoverRow,
    memory: RequestsMemory,
    art: RequestArt,
    restore: FocusRequester,
    onOpen: (MediaSummary) -> Unit,
    onSeeAll: (SeeAll) -> Unit,
) {
    var focused by remember(row.id) { mutableStateOf<MediaSummary?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(row.title, null)
            row.seeAll?.let { seeAll -> GlassButton(onClick = { onSeeAll(seeAll) }, height = 30.dp) { Text("Tout voir", fontSize = 13.sp) } }
        }
        LazyRow(
            state = memory.discoverRows.getOrPut(row.id) { LazyListState() },
            // Room around the cards for the focused one's scale and ring
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(row.items.distinctBy { it.key() }, key = { it.key() }) { media ->
                Box(Modifier.width(ROW_CARD_WIDTH.dp)) {
                    MediaCardTv(
                        media,
                        art,
                        modifier = if (media.key() == memory.opened) Modifier.focusRequester(restore) else Modifier,
                        onFocused = { focused = media },
                    ) { onOpen(media) }
                }
            }
        }
        // Reserved even when nothing is focused, so the rows below do not move
        Column(Modifier.height(40.dp).padding(horizontal = 48.dp)) {
            focused?.let {
                Text(it.title, color = Palette.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mediaMeta(it), color = Palette.textSecondary, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** A request on its way: small poster, title, its status in colour and, while it downloads, a thin progress bar. OK opens it. */
@Composable
internal fun InFlightCard(request: RequestView, art: RequestArt, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val media = request.media
    val line = requestLine(request)
    val still = reducedMotion()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.surface,
            contentColor = Palette.text,
            focusedContainerColor = Palette.surfaceRaised,
            focusedContentColor = Palette.text,
            pressedContainerColor = Palette.surfaceRaised,
            pressedContentColor = Palette.text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.04f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.accent), shape = shape)),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(6.dp)).background(Palette.surfaceRaised)) {
                RequestImage(art, media?.posterPath, "w185", Modifier.fillMaxHeight().fillMaxWidth())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(media?.title ?: "Demande n° ${request.id}", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line.text, color = line.tone.color(), fontSize = 12.sp, maxLines = 1)
                if (media?.status == MediaStatus.Downloading) {
                    media.progress?.let { ProgressBar(it.toFloat(), line.tone.color()) }
                }
            }
        }
    }
}

/** A thin bar filled to [fraction] (0 to 1). */
@Composable
internal fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.surfaceRaised)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}
