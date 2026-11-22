package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.Segment
import io.github.quentinpn.viewer.tv.emby.SegmentKind
import kotlin.math.ceil

// "Minuterie" of the track menu: times are passed in, milliseconds of the wall clock.

/** Minutes offered, after « Désactivée ». */
val SLEEP_MINUTES: List<Int> = listOf(15, 30, 45, 60, 90)

/** Under this much of the file left, « Fin de l'épisode » without credits pauses: positions come about once a second. */
const val END_OF_ITEM_MARGIN_SECONDS = 1.0

private const val MINUTE_MS = 60_000L

/** What « Fin de… » is called, and whether it is offered: only for an episode or a film. */
enum class SleepItem(val end: String) {
    Episode("Fin de l'épisode"),
    Movie("Fin du film"),
    ;

    companion object {
        /** From Emby's item type; null for anything else. */
        fun of(type: String?): SleepItem? = when (type) {
            "Episode" -> Episode
            "Movie" -> Movie
            else -> null
        }
    }
}

/** A value of the « Minuterie » row. */
sealed interface SleepChoice {
    data object Off : SleepChoice

    data class Minutes(val minutes: Int) : SleepChoice

    /** At the start of the credits if they are known, else at the end of the file. */
    data object EndOfItem : SleepChoice
}

/** ◀ ▶ step and hold at both ends, as « Vitesse »; OK goes to the next value and wraps. */
enum class SleepChange { Previous, Next, Cycle }

/** The row's values in order; « Fin de… » last, only for an episode or a film. */
fun sleepChoices(item: SleepItem?): List<SleepChoice> = buildList {
    add(SleepChoice.Off)
    SLEEP_MINUTES.forEach { add(SleepChoice.Minutes(it)) }
    if (item != null) add(SleepChoice.EndOfItem)
}

/**
 * The timer of one player session. [deadline] is when a [SleepChoice.Minutes] runs out; it
 * keeps running while paused, as a TV's. [from] is the position « Fin de… » was set at: credits
 * already started then do not count, the end of the file does.
 */
data class SleepTimer(val choice: SleepChoice = SleepChoice.Off, val deadline: Long = 0, val from: Double = 0.0) {
    val on get() = choice != SleepChoice.Off

    /** « Fin de… » set: the up-next card and the next episode must not come. */
    val holdsNext get() = choice == SleepChoice.EndOfItem
}

/** The timer for [choice], set now; a choice of minutes runs from [nowMs]. */
fun sleepTimer(choice: SleepChoice, nowMs: Long, position: Double): SleepTimer = when (choice) {
    SleepChoice.Off -> SleepTimer()
    is SleepChoice.Minutes -> SleepTimer(choice, deadline = nowMs + choice.minutes * MINUTE_MS)
    SleepChoice.EndOfItem -> SleepTimer(choice, from = position)
}

/** The value after [change] from [current]; a value no longer offered starts again from « Désactivée ». */
fun changeSleep(current: SleepChoice, change: SleepChange, item: SleepItem?): SleepChoice {
    val choices = sleepChoices(item)
    val index = choices.indexOf(current).coerceAtLeast(0)
    return when (change) {
        SleepChange.Previous -> choices[(index - 1).coerceAtLeast(0)]
        SleepChange.Next -> choices[(index + 1).coerceAtMost(choices.lastIndex)]
        SleepChange.Cycle -> choices[(index + 1) % choices.size]
    }
}

/**
 * Where « Fin de… » pauses: the start of the first credits after [from], else the end of
 * [duration]. Null while the duration is unknown and no credits are known.
 */
fun sleepStop(segments: List<Segment>, duration: Double, from: Double): Double? =
    segments.filter { it.kind == SegmentKind.Credits && it.start > from }.minOfOrNull { it.start }
        ?: duration.takeIf { it > 0 }?.let { (it - END_OF_ITEM_MARGIN_SECONDS).coerceAtLeast(0.0) }

/** Whether the timer has run out at [nowMs], or « Fin de… » has reached its stop at [position]. */
fun sleepDue(timer: SleepTimer, nowMs: Long, position: Double, duration: Double, segments: List<Segment>): Boolean = when (timer.choice) {
    SleepChoice.Off -> false
    is SleepChoice.Minutes -> nowMs >= timer.deadline
    SleepChoice.EndOfItem -> sleepStop(segments, duration, timer.from)?.let { position >= it } == true
}

/** Whole minutes left, rounded up: « 1 min » until it runs out. Null unless minutes are set. */
fun sleepMinutesLeft(timer: SleepTimer, nowMs: Long): Int? {
    if (timer.choice !is SleepChoice.Minutes) return null
    return ceil((timer.deadline - nowMs).coerceAtLeast(0) / MINUTE_MS.toDouble()).toInt()
}

/** The menu's value. */
fun sleepValue(choice: SleepChoice, item: SleepItem?): String = when (choice) {
    SleepChoice.Off -> "Désactivée"
    is SleepChoice.Minutes -> "${choice.minutes} min"
    SleepChoice.EndOfItem -> (item ?: SleepItem.Movie).end
}

/** Shown in the controls while the timer is set; null when it is not. */
fun sleepBadge(timer: SleepTimer, nowMs: Long, item: SleepItem?): String? = when (timer.choice) {
    SleepChoice.Off -> null
    is SleepChoice.Minutes -> "Arrêt dans ${sleepMinutesLeft(timer, nowMs)} min"
    SleepChoice.EndOfItem -> "Arrêt : ${(item ?: SleepItem.Movie).end.replaceFirstChar { it.lowercase() }}"
}

/** Shown over the paused picture once the timer has run out. */
const val SLEEP_NOTICE = "Minuterie écoulée : lecture en pause"
