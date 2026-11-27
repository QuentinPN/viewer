package io.github.quentinpn.viewer.tv.stremio

import java.time.Duration

/** From this share of the duration on, the content is seen: its entry goes, and the list offers no resume. */
const val RESUME_DONE_RATIO = 0.95

/** Before this position nothing is written (an entry already there stays). */
private const val RESUME_MIN_SECONDS = 60.0

/** How often a playback writes its position at most. */
private val RESUME_SAVE_EVERY: Duration = Duration.ofSeconds(15)

/** A resumed playback this far past its start began where asked. */
private const val START_SETTLED = 5.0

/** Where putting a start past the end back to 0 is. */
private enum class Fix { None, Pending, Done, Failed }

/**
 * Follows one Stremio playback of [target] into [store], fed the engine's events, as the desktop's
 * `ResumeTracker`: it writes where the playback stopped, and puts a start ([start], seconds; null from the
 * beginning) past the end of a shorter file back to 0, holding back the one end of file that start caused, so
 * it is not taken for a link that broke off.
 *
 * [fix] seeks to 0 and resumes (keep-open pauses at the end), true when sent; [spawn] runs it off the event
 * thread; [release] hands the end held back on to the player when [fix] failed. Thread-safe: events come on
 * mpv's thread, the lifecycle calls on the main one.
 */
class ResumeTracker(
    private val store: ResumeStore,
    private val target: String,
    start: Double?,
    private val fix: () -> Boolean,
    private val spawn: (() -> Unit) -> Unit,
    private val release: () -> Unit,
) {
    private val lock = Any()

    /** False once saved for good: later events change nothing. */
    private var following = true

    /** Its FileLoaded came: before it, nothing is the file's. */
    private var loaded = false
    private var duration = 0.0
    private var pos = 0.0
    private var hasPos = false
    private var saved = store.now()

    /** The last position since FileLoaded, recorded or not. */
    private var seen = 0.0
    private var hasSeen = false

    /** The start asked, while not known to be inside the file. */
    private var check = start?.takeIf(::finitePositive) ?: 0.0

    // A start past the end put back to 0: positions from ignoreFrom on are still the end's until one under it;
    // holdEnd holds back one end of file, the first, until then; held is that end while the fix has not answered.
    private var fixState = Fix.None
    private var ignoring = false
    private var ignoreFrom = 0.0
    private var holdEnd = false
    private var held = false

    fun fileLoaded() = synchronized(lock) {
        loaded = true
        checkStart()
    }

    fun duration(seconds: Double) {
        synchronized(lock) {
            if (!following || !finitePositive(seconds)) return
            duration = seconds
            checkStart()
        }
    }

    /** The start asked against the duration, once both are known (mpv may send the duration before FileLoaded). */
    private fun checkStart() {
        if (!loaded || check <= 0 || duration <= 0) return
        if (check >= duration) fixStart(duration)
        check = 0.0
    }

    fun position(seconds: Double) {
        synchronized(lock) {
            if (!following || !loaded || !seconds.isFinite() || seconds < 0) return
            seen = seconds
            hasSeen = true
            if (ignoring) {
                if (seconds >= ignoreFrom) return
                // Playing from 0: any end of file from here on is a real one
                ignoring = false
                holdEnd = false
            }
            if (check > 0) {
                if (seconds < check + START_SETTLED) return
                check = 0.0
            }
            pos = seconds
            hasPos = true
            val now = store.now()
            if (Duration.between(saved, now) >= RESUME_SAVE_EVERY) {
                persist(eof = false)
                saved = now
            }
        }
    }

    /**
     * The file reached its end (`eof-reached`): saved, the entry deleted at a real end, kept for a stream cut
     * short. False when the player must not see it (the end of a start past the end).
     */
    fun endReached(): Boolean = synchronized(lock) {
        if (!following || !loaded) return@synchronized true
        // No duration came first: the start is past the end only when mpv stopped short of it
        if (check > 0 && hasSeen && seen < check - 1) fixStart(check)
        if (holdEnd) {
            holdEnd = false
            if (fixState == Fix.Pending) held = true
            return@synchronized false
        }
        finish(eof = true)
        true
    }

    /** The file failed or broke off (`END_FILE` under keep-open): its position is kept. */
    fun endFile() = synchronized(lock) { finish(eof = false) }

    /** The player leaves the screen: where it is now is written, the playback still followed. */
    fun save() = synchronized(lock) { if (following) persist(eof = false) }

    /** The player goes: saved, and no longer followed. */
    fun end() = synchronized(lock) { finish(eof = false) }

    /** Back to 0 off the event thread; positions from half of [span] (the duration, else the start) on are still the end's. */
    private fun fixStart(span: Double) {
        check = 0.0
        fixState = Fix.Pending
        ignoring = true
        ignoreFrom = span / 2
        holdEnd = true
        spawn(::runFix)
    }

    /** The lock is held across the fix, so [end] waits for it. A failure hands the end held back on to the player. */
    private fun runFix() {
        val released = synchronized(lock) {
            if (!following) return
            if (fix()) {
                fixState = Fix.Done
                held = false
                return
            }
            fixState = Fix.Failed
            ignoring = false
            holdEnd = false
            held.also {
                held = false
                if (it) finish(eof = true)
            }
        }
        if (released) release()
    }

    private fun finish(eof: Boolean) {
        if (!following) return
        persist(eof)
        following = false
    }

    /**
     * Writes by the rules: nothing before [RESUME_MIN_SECONDS] or without a duration, the entry deleted at the
     * end (an end of file there, or [RESUME_DONE_RATIO]), else saved. An end of file well before the end is a
     * stream cut short: its position is kept, the list reopens on it.
     */
    private fun persist(eof: Boolean) {
        if (!hasPos) return
        val d = duration
        when {
            d <= 0 -> if (eof) store.delete(target)
            pos >= RESUME_DONE_RATIO * d -> store.delete(target)
            pos < RESUME_MIN_SECONDS -> Unit
            else -> store.put(target, ResumePoint(pos, d))
        }
    }

    override fun toString() = "ResumeTracker"
}
