package io.github.quentinpn.viewer.tv.ui.stremio

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.stremio.defaultSeason
import io.github.quentinpn.viewer.tv.stremio.episodeNumbers
import io.github.quentinpn.viewer.tv.stremio.pickableSeasons
import io.github.quentinpn.viewer.tv.stremio.seasonLabel
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.ui.requests.ChoiceDialog
import io.github.quentinpn.viewer.tv.ui.requests.DialogCard
import io.github.quentinpn.viewer.tv.ui.requests.FocusOnOpen
import io.github.quentinpn.viewer.tv.request.Season

private const val TITLE = "Choisir un épisode"

/**
 * « Regarder maintenant » on a Demander series: a season, then an episode, whose sources open next, as on the
 * desktop. A series with a single season goes straight to its episodes. Back from the episodes returns to the
 * seasons (the one picked stays chosen); Back from the seasons, or from the only season, dismisses.
 * [initialSeason] is the season of the last episode picked.
 */
@Composable
internal fun EpisodePickerTv(
    seasons: List<Season>,
    initialSeason: Int?,
    onPick: (season: Int, episode: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = remember(seasons) { pickableSeasons(seasons) }
    if (choices.isEmpty()) {
        NoEpisodes(onDismiss)
        return
    }
    val numbers = choices.map { it.seasonNumber }
    var season by remember(choices) { mutableStateOf(numbers.singleOrNull()) }
    var last by remember(choices) { mutableStateOf(initialSeason?.takeIf { it in numbers } ?: defaultSeason(choices)) }
    val current = season
    if (current == null) {
        ChoiceDialog(
            TITLE,
            labels = numbers.map(::seasonLabel),
            selected = numbers.indexOf(last).coerceAtLeast(0),
            onPick = { i ->
                last = numbers[i]
                season = numbers[i]
            },
            onDismiss = onDismiss,
        )
    } else {
        val count = choices.first { it.seasonNumber == current }.episodeCount
        ChoiceDialog(
            "$TITLE · ${seasonLabel(current)}",
            labels = episodeNumbers(count).map { "Épisode $it" },
            selected = 0,
            onPick = { i -> onPick(current, i + 1) },
            // Back to the seasons, unless there was only one to choose from
            onDismiss = { if (numbers.size > 1) season = null else onDismiss() },
        )
    }
}

@Composable
private fun NoEpisodes(onDismiss: () -> Unit) {
    val close = remember { FocusRequester() }
    DialogCard(TITLE, onDismiss) {
        Text("Aucun épisode connu pour cette série.", color = Palette.textSecondary)
        Row { OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(close)) { Text("Annuler") } }
    }
    FocusOnOpen(close)
}
