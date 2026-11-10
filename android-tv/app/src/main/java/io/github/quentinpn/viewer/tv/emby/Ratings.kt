package io.github.quentinpn.viewer.tv.emby

import java.util.Locale
import kotlin.math.roundToInt

// Ratings of the film, series and episode pages (ADR 0074). The three are base fields of
// `BaseItemDto`, returned by `/Users/{Id}/Items/{Id}` whatever `Fields` says.

/** `★ 7,4`: the public's score out of 10, one decimal; none when absent or 0 (not rated). */
fun communityRatingLabel(rating: Double?): String? {
    val score = rating?.takeIf { it.isFinite() && it > 0 } ?: return null
    return "★ " + String.format(Locale.FRANCE, "%.1f", score)
}

/** `87 % critiques`: the critics' score out of 100 (Windows « Critiques »); 0 is a real score. */
fun criticRatingLabel(rating: Double?): String? {
    val score = rating?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
    // No-break space before %, as French typography wants
    return "${score.roundToInt()} % critiques"
}

/** `12`, `TV-MA`: the age rating as the server writes it, for the outlined badge. */
fun ageRatingLabel(rating: String?): String? = rating?.trim()?.takeIf { it.isNotEmpty() }
