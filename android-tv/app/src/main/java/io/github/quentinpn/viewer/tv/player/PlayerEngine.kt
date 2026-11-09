package io.github.quentinpn.viewer.tv.player

import android.view.Surface

/** Events are delivered on the engine's event thread; listeners hop to the UI thread themselves. */
interface PlayerListener {
    fun onFileLoaded() {}

    fun onEndOfFile() {}

    fun onPauseChanged(paused: Boolean) {}

    /** Playback position, whole seconds: about one call per second. */
    fun onPosition(seconds: Double) {}

    fun onDuration(seconds: Double) {}

    /** A seek started; [onPosition] then gives the new position. */
    fun onSeek() {}

    /** The last frame is shown: the file stays open (keep-open), playback is over. */
    fun onEndReached() {}
}

/**
 * Transport-neutral playback engine, as on Windows (ARCHITECTURE.md). The UI
 * never talks to libmpv directly, so Media3 could stand in later (ADR 0038).
 */
interface PlayerEngine {
    fun addListener(listener: PlayerListener)

    fun removeListener(listener: PlayerListener)

    fun attachSurface(surface: Surface)

    fun resizeSurface(width: Int, height: Int)

    fun detachSurface()

    /**
     * Playback begins at [startSeconds]. HTTP headers (the token) are given when the
     * engine is created, never in the URL.
     */
    fun load(source: MediaSource, startSeconds: Double = 0.0)

    fun addSubtitle(source: MediaSource)

    fun togglePause()

    fun seekBy(seconds: Double)

    fun cycleSubtitle()

    fun cycleAudio()

    /** Diagnostics only: reads one libmpv property as text. */
    fun property(name: String): String?

    /** Idempotent. */
    fun release()
}
