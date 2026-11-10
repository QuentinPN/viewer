package io.github.quentinpn.viewer.tv.player

// A held ◀ ▶ seeks faster the longer it is held (ADR 0090). Android repeats a held
// key about 20 times a second, after a ~0.4 s delay; KeyEvent.repeatCount counts them.

/** The step of a press and of the first second of repeats. */
const val SEEK_STEP_SECONDS = 10.0

/** Repeats before the step grows to 30 s (about 1 s of holding), then to 60 s (about 3 s). */
const val SEEK_MEDIUM_FROM = 20
const val SEEK_LARGE_FROM = 60

/** A hold seeks on every this many repeats only: about 5 seeks a second instead of 20. */
const val SEEK_REPEAT_EVERY = 4

/** The step for a key event with [repeatCount] repeats; a fresh press (0) is 10 s. */
fun seekStep(repeatCount: Int): Double = when {
    repeatCount >= SEEK_LARGE_FROM -> 60.0
    repeatCount >= SEEK_MEDIUM_FROM -> 30.0
    else -> SEEK_STEP_SECONDS
}

/** Whether the key event with [repeatCount] repeats seeks: the press always, then every [SEEK_REPEAT_EVERY]th repeat. */
fun seeksOnRepeat(repeatCount: Int): Boolean = repeatCount % SEEK_REPEAT_EVERY == 0
