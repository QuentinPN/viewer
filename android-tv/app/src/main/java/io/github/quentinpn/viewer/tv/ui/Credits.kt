package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.ageRatingLabel
import io.github.quentinpn.viewer.tv.emby.communityRatingLabel
import io.github.quentinpn.viewer.tv.emby.criticRatingLabel

/** A labelled line of the detail page, as in the Windows facts list. */
internal data class Fact(val label: String, val value: String)

/**
 * Director, writer and studio of a film, series or episode; empty ones left out. The cast,
 * once an « Avec » / « Invités » line, is the « Distribution » row now (ADR 0068).
 */
internal fun facts(item: BaseItemDto): List<Fact> {
    val series = item.type == "Series"
    fun people(type: String) = item.people.filter { it.type == type }.map { it.name.trim() }.filter { it.isNotEmpty() }
    return listOfNotNull(
        people("Director").takeIf { it.isNotEmpty() }?.let { Fact("Réalisation", it.joinToString(", ")) },
        people("Writer").takeIf { it.isNotEmpty() && !series }?.let { Fact("Scénario", it.joinToString(", ")) },
        item.studios.map { it.name.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
            ?.let { Fact(if (series) "Diffusion" else "Studio", it.joinToString(", ")) },
    )
}

/**
 * Genres and scores after the year: "Action, Fantastique · ★ 7,4 · 87 % critiques". The age
 * rating is the [RatingBadge] before them (ADR 0074).
 */
internal fun tags(item: BaseItemDto): List<String> = listOfNotNull(
    item.genres.filter { it.isNotBlank() }.take(3).takeIf { it.isNotEmpty() }?.joinToString(", "),
) + scores(item)

/** The public's and the critics' scores, the ones the server has (ADR 0074). */
internal fun scores(item: BaseItemDto): List<String> =
    listOfNotNull(communityRatingLabel(item.communityRating), criticRatingLabel(item.criticRating))

/** The age rating (« 12 », « TV-MA ») in a thin outline, quieter than the filled « Vu ». */
@Composable
internal fun RatingBadge(item: BaseItemDto) {
    val rating = ageRatingLabel(item.officialRating) ?: return
    Text(
        rating,
        color = Palette.textSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, Palette.textSecondary, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = "Classification $rating" },
    )
}

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
