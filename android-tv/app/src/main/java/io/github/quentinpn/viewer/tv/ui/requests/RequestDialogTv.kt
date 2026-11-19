package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.request.MediaDetails
import io.github.quentinpn.viewer.tv.request.MediaType
import io.github.quentinpn.viewer.tv.request.NewRequest
import io.github.quentinpn.viewer.tv.request.RequestQuota
import io.github.quentinpn.viewer.tv.request.Season
import io.github.quentinpn.viewer.tv.request.SeasonsChoice
import io.github.quentinpn.viewer.tv.request.RequestException
import io.github.quentinpn.viewer.tv.request.RequestRepository
import kotlinx.coroutines.launch

/**
 * « Demander » over a title's page: a film is a confirmation; a series lists its seasons with
 * check boxes, the ones that cannot be asked for dimmed with their status. The quota shows before
 * the button, which is off when it would be exceeded. A refusal of the server shows as it came.
 * [onRequested] runs once the request is made; the page then reads the title again.
 */
@Composable
internal fun RequestDialogTv(
    repository: RequestRepository,
    details: MediaDetails,
    onRequested: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = remember { isoDay() }
    val seasons = remember(details) { details.seasons.filter { it.seasonNumber > 0 } }
    val firstRequestable = seasons.firstOrNull { isSeasonRequestable(it, today) }?.seasonNumber
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var quota by remember { mutableStateOf<RequestQuota?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    // Opens on the first season that can be ticked, composed so it takes the focus whatever its place in the list
    val list = remember { LazyListState(firstVisibleItemIndex = seasons.indexOfFirst { it.seasonNumber == firstRequestable }.coerceAtLeast(0)) }
    // Without the quota the dialog still works: the server has the last word
    LaunchedEffect(Unit) { quota = tried { repository.quota() }.getOrNull() }

    val isSeries = details.type == MediaType.Tv
    // A series whose seasons the server does not list is asked for whole
    val wholeSeries = isSeries && seasons.isEmpty()
    val chooses = isSeries && !wholeSeries
    val count = if (chooses) picked.size else 1
    val exceeds = exceedsQuota(quota, details.type, count)
    val submit = {
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                val request = NewRequest(details.type, details.tmdbId, if (chooses) SeasonsChoice.Some(picked.sorted()) else null)
                tried { repository.request(request) }.fold(
                    { onRequested() },
                    {
                        error = it.requestMessage()
                        busy = false
                    },
                )
            }
        }
    }
    DialogCard("Demander « ${details.title} »", onDismiss, width = 560.dp) {
        if (chooses) {
            Text("Coche les saisons à demander.", color = Palette.textSecondary)
            LazyColumn(
                Modifier.heightIn(max = 300.dp),
                state = list,
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(seasons, key = { it.seasonNumber }) { season ->
                    SeasonRow(
                        season,
                        today,
                        checked = season.seasonNumber in picked,
                        modifier = if (season.seasonNumber == firstRequestable) Modifier.focusRequester(first) else Modifier,
                    ) { picked = if (season.seasonNumber in picked) picked - season.seasonNumber else picked + season.seasonNumber }
                }
            }
        } else {
            Text(if (wholeSeries) "Cette série sera demandée en entier." else "Ce film sera demandé à Request.", color = Palette.textSecondary)
        }
        quota?.let { quotaLine(it, details.type) }?.let { Text(it, color = if (exceeds) Palette.warning else Palette.textSecondary) }
        if (exceeds) Text("Cela dépasse ton quota.", color = Palette.warning)
        error?.let { Text(it, color = Palette.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onClick = onDismiss, height = BUTTON_HEIGHT) { Text("Annuler") }
            GlassButton(
                onClick = submit,
                // Off but still focusable, with its ring: it says what is missing
                dimmed = busy || count == 0 || exceeds,
                height = BUTTON_HEIGHT,
                modifier = if (firstRequestable == null || !chooses) Modifier.focusRequester(first) else Modifier,
            ) {
                Text(if (busy) "Envoi…" else if (chooses) (if (count == 0) "Choisissez des saisons" else seasonsLabel(count)) else "Demander")
            }
        }
    }
    FocusOnOpen(first)
}

/** One season: a check box and its name; a season that cannot be asked for is dimmed, with its status, and skipped by the focus. */
@Composable
private fun SeasonRow(season: Season, today: String, checked: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val note = seasonNote(season, today)
    val shape = RoundedCornerShape(10.dp)
    val dim = note != null
    val ink = if (dim) Palette.textSecondary.copy(alpha = 0.6f) else Palette.text
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBox(checked && !dim)
            Text("Saison ${season.seasonNumber}", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            note?.let { Text(it, fontSize = 13.sp, color = ink) }
        }
    }
    if (dim) {
        // Not a control: dimmed and out of the focus path
        Box(Modifier.fillMaxWidth().background(Palette.surfaceRaised.copy(alpha = 0.35f), shape)) { content() }
    } else {
        Surface(
            onClick = onToggle,
            modifier = modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Palette.surfaceRaised,
                contentColor = Palette.text,
                focusedContainerColor = Palette.surfaceRaised,
                focusedContentColor = Palette.text,
                pressedContainerColor = Palette.surfaceRaised,
                pressedContentColor = Palette.text,
            ),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), shape = shape)),
        ) { content() }
    }
}

@Composable
private fun CheckBox(checked: Boolean) {
    Box(
        Modifier.size(22.dp)
            .background(if (checked) Palette.text else Color.Transparent, RoundedCornerShape(6.dp))
            .border(2.dp, if (checked) Palette.text else Palette.textSecondary, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
