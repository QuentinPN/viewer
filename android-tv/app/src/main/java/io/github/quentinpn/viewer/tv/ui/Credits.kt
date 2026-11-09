package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import java.util.Locale

private const val MAX_CAST = 6

/** A labelled line of the detail page, as in the Windows facts list. */
internal data class Fact(val label: String, val value: String)

/** Director, writer, cast and studio of a film or series; empty ones left out. */
internal fun facts(item: BaseItemDto): List<Fact> {
    val series = item.type == "Series"
    fun people(type: String) = item.people.filter { it.type == type }.map { it.name.trim() }.filter { it.isNotEmpty() }
    return listOfNotNull(
        people("Director").takeIf { it.isNotEmpty() }?.let { Fact("Réalisation", it.joinToString(", ")) },
        people("Writer").takeIf { it.isNotEmpty() && !series }?.let { Fact("Scénario", it.joinToString(", ")) },
        people("Actor").takeIf { it.isNotEmpty() }?.let { Fact("Avec", it.take(MAX_CAST).joinToString(", ")) },
        item.studios.map { it.name.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
            ?.let { Fact(if (series) "Diffusion" else "Studio", it.joinToString(", ")) },
    )
}

/** Rating, genres and score after the year: "TV-14 · Action, Fantastique · ★ 7,4". */
internal fun tags(item: BaseItemDto): List<String> = listOfNotNull(
    item.officialRating?.trim()?.takeIf { it.isNotEmpty() },
    item.genres.filter { it.isNotBlank() }.take(3).takeIf { it.isNotEmpty() }?.joinToString(", "),
    item.communityRating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.FRANCE, "%.1f", it) },
)

@Composable
internal fun Facts(item: BaseItemDto, limit: Int = Int.MAX_VALUE) {
    val shown = facts(item).take(limit)
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { fact ->
            Row {
                Text(fact.label, color = Palette.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(112.dp).alignByBaseline())
                Text(
                    fact.value,
                    color = Palette.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}
