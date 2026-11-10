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

    /** "Passer automatiquement" (ADR 0051): seek to [to] without waiting for a press. */
    data class Auto(val to: Double) : SkipPrompt
}

/**
 * What to show at [position]. The up-next card comes once per credits segment and
 * playback; once dismissed, those credits get the "Passer le générique" button.
 * With [auto], each segment is skipped once per playback, as on Windows: seeking
 * back into it shows the button again. The up-next card counts as that skip.
 */
class SkipPlan(private val segments: List<Segment>, private val auto: Boolean = false) {
    private val offered = mutableSetOf<Segment>()
    private val skipped = mutableSetOf<Segment>()

    fun at(position: Double, duration: Double, hasNext: Boolean): SkipPrompt? {
        val segment = activeSegment(segments, position, duration) ?: return null
        val end = segmentEnd(segment, duration) ?: return null
        val intro = segment.kind == SegmentKind.Intro
        return when {
            !intro && hasNext && offered.add(segment) -> SkipPrompt.UpNext.also { skipped += segment }
            auto && skipped.add(segment) -> SkipPrompt.Auto(end)
            intro -> SkipPrompt.Button("Passer l'intro", end)
            else -> SkipPrompt.Button("Passer le générique", end)
        }
    }
}

/** How long the button shows on its own once its segment starts (ADR 0089), as on Windows. */
const val SKIP_SHOWN_MS = 10_000L

/**
 * When [button] appeared. It is null outside any segment and while the up-next card
 * shows, so leaving a segment (its end, or a seek before its start) and entering
 * another start a new countdown.
 */
data class SkipShown(val button: SkipPrompt.Button? = null, val since: Long = 0L)

/** The countdown for [button] at [now]; unchanged within the same segment. */
fun trackSkipShown(prev: SkipShown, button: SkipPrompt.Button?, now: Long): SkipShown =
    if (button == prev.button) prev else SkipShown(button, now)

/** Whether the button shows: for [SKIP_SHOWN_MS] after it appeared, then only with the [controls], until the segment ends. */
fun skipShown(shown: SkipShown, now: Long, controls: Boolean): Boolean =
    shown.button != null && (controls || now - shown.since < SKIP_SHOWN_MS)
