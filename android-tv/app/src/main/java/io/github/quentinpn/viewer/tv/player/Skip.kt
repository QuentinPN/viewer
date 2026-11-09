package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.Segment
import io.github.quentinpn.viewer.tv.emby.SegmentKind

// "Passer l'intro" and "Passer le générique" (ADR 0030, 0048), as src/lib/player/segments.ts.

/** Credits ending this close to the end of the file run to the end. */
const val END_SLACK_SECONDS = 2.0

/** The button is gone this close to a segment's end: skipping would do nothing. */
const val HIDE_BEFORE_END_SECONDS = 0.5

/** Where a segment ends; credits with no end, or near it, end with the file. Null while the duration is unknown. */
fun segmentEnd(segment: Segment, duration: Double): Double? {
    val end = segment.end ?: return duration.takeIf { it > 0 }
    return if (duration > 0 && duration - end <= END_SLACK_SECONDS) duration else end
}

/** The segment being played, if its prompt should show. */
fun activeSegment(segments: List<Segment>, position: Double, duration: Double): Segment? = segments.firstOrNull {
    val end = segmentEnd(it, duration)
    end != null && position >= it.start && position < end - HIDE_BEFORE_END_SECONDS
}

/** What the player offers during a segment. */
sealed interface SkipPrompt {
    /** A button that seeks to [to]; the end of the file closes the player as a natural end. */
    data class Button(val label: String, val to: Double) : SkipPrompt

    /** The "Épisode suivant" card (ADR 0043), at the start of the credits. */
    data object UpNext : SkipPrompt
}

/**
 * What to show at [position]. The up-next card comes once per credits segment and
 * playback; once dismissed, those credits get the "Passer le générique" button.
 */
class SkipPlan(private val segments: List<Segment>) {
    private val offered = mutableSetOf<Segment>()

    fun at(position: Double, duration: Double, hasNext: Boolean): SkipPrompt? {
        val segment = activeSegment(segments, position, duration) ?: return null
        val end = segmentEnd(segment, duration) ?: return null
        return when {
            segment.kind == SegmentKind.Intro -> SkipPrompt.Button("Passer l'intro", end)
            hasNext && offered.add(segment) -> SkipPrompt.UpNext
            else -> SkipPrompt.Button("Passer le générique", end)
        }
    }
}
