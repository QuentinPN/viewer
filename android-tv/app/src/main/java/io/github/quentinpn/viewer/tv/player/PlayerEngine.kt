package io.github.quentinpn.viewer.tv.player

import android.view.Surface
import io.github.quentinpn.viewer.tv.settings.SubtitleStyle

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

    /** mpv waits for its cache (`paused-for-cache`), or plays again. */
    fun onBuffering(buffering: Boolean) {}
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
     * Playback begins at [startSeconds], unpaused unless [paused] (a linked load, ADR 0124); a loaded file is replaced. HTTP headers (the token) are given when the
     * engine is created, never in the URL.
     */
    fun load(source: MediaSource, startSeconds: Double = 0.0, paused: Boolean = false)

    /**
     * Adds an external subtitle file to the loaded one; [select] makes it the shown track.
     * [title] and [lang] name the track, as the server describes it (ADR 0084).
     */
    fun addSubtitle(source: MediaSource, select: Boolean = true, title: String? = null, lang: String? = null)

    fun togglePause()

    fun setPaused(paused: Boolean)

    /** The exact position, in seconds, unlike [PlayerListener.onPosition]; null before a file plays. */
    fun positionSeconds(): Double?

    /** Seconds of the file read ahead in the cache, and whether the cache has stopped reading (all of it is there). */
    fun cacheAhead(): Pair<Double, Boolean>

    fun seekBy(seconds: Double)

    /** Absolute seek; at or past the duration, the file reaches its end (keep-open). */
    fun seekTo(seconds: Double)

    fun cycleSubtitle()

    fun cycleAudio()

    /** Audio and subtitle tracks of the loaded file; empty before it is loaded. */
    fun tracks(): List<Track>

    /** Selects a track by its id; `null` turns subtitles (or audio) off. */
    fun selectTrack(type: TrackType, id: Int?)

    /** Vertical place of subtitles, in % of the picture height: 100 is their normal place, lower moves them up. */
    fun setSubtitlePosition(percent: Int)

    /** Look of text subtitles, from Réglages; [setSubtitlePosition] places them. */
    fun setSubtitleStyle(style: SubtitleStyle)

    /** Sets a delay, in seconds, for the loaded file only (ADR 0062); returns the value mpv then holds, null if unreadable. */
    fun setDelay(kind: DelayKind, seconds: Double): Double?

    /** Playback speed for the loaded file only (ADR 0072); returns the value mpv then holds, null if unreadable. */
    fun setSpeed(speed: Double): Double?

    /** How the picture fills the screen, for the loaded file only (ADR 0078); returns the fit mpv then holds, null if unreadable. */
    fun setVideoFit(fit: VideoFit): VideoFit?

    /** Night mode's compressor on or off (ADR 0072); true when mpv holds it as asked. Kept for the next files. */
    fun setNightMode(on: Boolean): Boolean

    /** Audio leaves undecoded (spdif passthrough): no audio filter can apply to it. */
    fun audioPassthrough(): Boolean

    /** Diagnostics only: reads one libmpv property as text. */
    fun property(name: String): String?

    /** Idempotent. */
    fun release()
}
