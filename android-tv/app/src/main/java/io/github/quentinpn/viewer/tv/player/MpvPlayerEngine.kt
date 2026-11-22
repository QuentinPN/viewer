package io.github.quentinpn.viewer.tv.player

import android.content.Context
import android.util.Log
import android.view.Surface
import io.github.quentinpn.viewer.tv.settings.SubtitleStyle
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvEvent
import `is`.xyz.mpv.MPVLib.MpvFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread

private const val TAG = "ViewerMpv"
private const val VO = "gpu"

/**
 * libmpv through mpv-android's JNI bridge. libplayer.so keeps a
 * single global mpv handle and aborts the process on a second create, so only
 * one engine may be alive at a time.
 *
 * [headers] are set once, before `mpv_initialize`: libmpv logs every later option
 * or property change with its value at verbose level, and libplayer copies each
 * libmpv log line to logcat itself, before [redactSecrets] can see it. Messages
 * emitted before init are never delivered. Later ones can still carry it (lavf lists
 * the headers an HLS transcode left unused): [NativeLog] drops that verbose copy.
 * They apply to every request of this engine, external subtitles included.
 */
class MpvPlayerEngine(context: Context, headers: Map<String, String> = emptyMap()) : PlayerEngine, MPVLib.EventObserver, MPVLib.LogObserver {
    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val released = AtomicBoolean(false)

    // Held by calls that may come from another thread than the UI's: mpv is destroyed only once they return
    private val calls = ReentrantReadWriteLock()
    private var surfaceAttached = false
    private var pending: PendingFile? = null

    init {
        check(alive.compareAndSet(false, true)) { "Un seul lecteur mpv peut exister à la fois" }
        NativeLog.ensureQuiet()
        MPVLib.create(context.applicationContext)
        for ((name, value) in OPTIONS) {
            val rc = MPVLib.setOptionString(name, value)
            if (rc < 0) Log.w(TAG, "option $name=$value refusée ($rc)")
        }
        // A comma would split the list: such a header is dropped rather than mangled
        val fields = headers.filterValues { ',' !in it }.map { (k, v) -> "$k: $v" }
        if (fields.isNotEmpty() && MPVLib.setOptionString("http-header-fields", fields.joinToString(",")) < 0) {
            Log.w(TAG, "option http-header-fields refusée")
        }
        MPVLib.init()
        // Must stay off until a surface exists, or VO init fails (mpv-android BaseMPVView)
        MPVLib.setOptionString("force-window", "no")
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        MPVLib.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
        // As an integer: changes once per second instead of on every frame
        MPVLib.observeProperty("time-pos", MpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
    }

    override fun addListener(listener: PlayerListener) {
        listeners += listener
    }

    override fun removeListener(listener: PlayerListener) {
        listeners -= listener
    }

    override fun attachSurface(surface: Surface) {
        if (released.get()) return
        MPVLib.attachSurface(surface)
        MPVLib.setOptionString("force-window", "yes")
        surfaceAttached = true
        val file = pending
        if (file != null) {
            pending = null
            loadNow(file)
        } else {
            MPVLib.setPropertyString("vo", VO)
        }
    }

    override fun resizeSurface(width: Int, height: Int) {
        if (released.get()) return
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun detachSurface() {
        if (released.get() || !surfaceAttached) return
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
        surfaceAttached = false
    }

    override fun load(source: MediaSource, startSeconds: Double, paused: Boolean) {
        if (released.get()) return
        // Per-file option: a later file does not inherit this start position
        val start = if (startSeconds > 0) startSeconds.toString() else "none"
        val file = PendingFile(source, "start=$start", paused)
        if (surfaceAttached) loadNow(file) else pending = file
    }

    private fun loadNow(file: PendingFile) {
        // keep-open paused the previous file on its last frame
        MPVLib.setPropertyString("pause", if (file.paused) "yes" else "no")
        // `aid` and `sid` are options too: a track selected in the last file would carry over (as on Windows)
        MPVLib.setPropertyString("aid", "auto")
        MPVLib.setPropertyString("sid", "auto")
        // So are the delays: each file starts in sync, the next episode too
        for (kind in DelayKind.entries) MPVLib.setPropertyDouble(kind.property, 0.0)
        // And the speed: each file starts at 1x
        MPVLib.setPropertyDouble(SPEED_PROPERTY, NORMAL_SPEED)
        // And the picture format: each file starts at « Original »
        for ((name, value) in fitProperties(VideoFit.Original)) MPVLib.setPropertyString(name, value)
        MPVLib.command(arrayOf("loadfile", file.source.uri, "replace", "-1", file.options))
    }

    /** Blocks until mpv has read the whole file: called off the UI and event threads. */
    override fun addSubtitle(source: MediaSource, select: Boolean, title: String?, lang: String?) = calls.read {
        if (released.get()) return@read
        // `auto` adds the track without selecting it, as on Windows; title and
        // language as Windows passes them, else mpv only knows the file name
        MPVLib.command(arrayOf("sub-add", source.uri, if (select) "select" else "auto", title.orEmpty(), lang.orEmpty()))
    }

    override fun togglePause() = command("cycle", "pause")

    override fun setPaused(paused: Boolean) {
        if (!released.get()) MPVLib.setPropertyBoolean("pause", paused)
    }

    override fun positionSeconds(): Double? = calls.read { if (released.get()) null else MPVLib.getPropertyDouble("time-pos") }

    override fun cacheAhead(): Pair<Double, Boolean> = calls.read {
        if (released.get()) {
            0.0 to false
        } else {
            (MPVLib.getPropertyDouble("demuxer-cache-duration") ?: 0.0) to (MPVLib.getPropertyBoolean("demuxer-cache-idle") ?: false)
        }
    }

    override fun seekBy(seconds: Double) = command("seek", seconds.toString(), "relative")

    override fun seekTo(seconds: Double) = command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute")

    override fun cycleSubtitle() = command("cycle", "sub")

    override fun cycleAudio() = command("cycle", "audio")

    override fun tracks(): List<Track> = calls.read { if (released.get()) emptyList() else readTracks { MPVLib.getPropertyString(it) } }

    override fun selectTrack(type: TrackType, id: Int?) = calls.read {
        if (!released.get()) MPVLib.setPropertyString(type.property, id?.toString() ?: "no")
    }

    override fun setSubtitlePosition(percent: Int) {
        if (!released.get()) MPVLib.setPropertyString("sub-pos", percent.coerceIn(0, 100).toString())
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        if (released.get()) return
        // Set as properties: they stay for the next files of this engine, and each item sets them again
        for ((name, value) in subtitleOptions(style)) MPVLib.setPropertyString(name, value)
    }

    override fun setDelay(kind: DelayKind, seconds: Double): Double? {
        if (released.get()) return null
        MPVLib.setPropertyDouble(kind.property, seconds)
        return MPVLib.getPropertyDouble(kind.property)
    }

    override fun setSpeed(speed: Double): Double? {
        if (released.get()) return null
        MPVLib.setPropertyDouble(SPEED_PROPERTY, speed)
        return MPVLib.getPropertyDouble(SPEED_PROPERTY)
    }

    override fun setVideoFit(fit: VideoFit): VideoFit? {
        if (released.get()) return null
        // VO options: mpv recomputes the picture's place on the next frame, no reload
        for ((name, value) in fitProperties(fit)) MPVLib.setPropertyString(name, value)
        return readFit(MPVLib.getPropertyString(PANSCAN_PROPERTY), MPVLib.getPropertyString(KEEPASPECT_PROPERTY))
    }

    override fun setNightMode(on: Boolean): Boolean {
        if (released.get()) return false
        // The JNI bridge drops mpv's error code: what `af` holds afterwards tells
        MPVLib.setPropertyString(AUDIO_FILTER_PROPERTY, nightFilter(on))
        return nightModeHeld(MPVLib.getPropertyString(AUDIO_FILTER_PROPERTY), on)
    }

    override fun audioPassthrough(): Boolean = !released.get() &&
        (isPassthrough(MPVLib.getPropertyString("audio-params/format")) || isPassthrough(MPVLib.getPropertyString("audio-out-params/format")))

    override fun property(name: String): String? = calls.read { if (released.get()) null else MPVLib.getPropertyString(name) }

    /**
     * `stop` ends a subtitle still loading (mpv aborts `sub-add` when playback ends); if
     * one does not return in time, mpv is destroyed on its own thread once it has, so the
     * UI thread never waits on the server.
     */
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        MPVLib.removeObserver(this)
        MPVLib.removeLogObserver(this)
        surfaceAttached = false
        MPVLib.command(arrayOf("stop"))
        val lock = calls.writeLock()
        if (lock.tryLock(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            destroy(lock)
        } else {
            Log.w(TAG, "release waits for a subtitle")
            thread(name = "viewer-mpv-release") {
                lock.lock()
                destroy(lock)
            }
        }
    }

    private fun destroy(lock: java.util.concurrent.locks.Lock) {
        try {
            MPVLib.destroy()
        } finally {
            lock.unlock()
            alive.set(false)
        }
    }

    private fun command(vararg args: String) {
        if (!released.get()) MPVLib.command(arrayOf(*args))
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> listeners.forEach { it.onFileLoaded() }
            MpvEvent.MPV_EVENT_END_FILE -> listeners.forEach { it.onEndOfFile() }
            MpvEvent.MPV_EVENT_SEEK -> listeners.forEach { it.onSeek() }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> listeners.forEach { it.onPauseChanged(value) }
            "eof-reached" -> if (value) listeners.forEach { it.onEndReached() }
            "paused-for-cache" -> listeners.forEach { it.onBuffering(value) }
        }
    }

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {
        if (property == "time-pos") listeners.forEach { it.onPosition(value.toDouble()) }
    }

    override fun eventProperty(property: String, value: String) {}

    override fun eventProperty(property: String, value: Double) {
        if (property == "duration") listeners.forEach { it.onDuration(value) }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        val priority = when {
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> Log.ERROR
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> Log.WARN
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO -> Log.INFO
            else -> return
        }
        Log.println(priority, TAG, redactSecrets("[$prefix] ${text.trimEnd()}"))
    }

    private companion object {
        val alive = AtomicBoolean(false)

        const val RELEASE_WAIT_MS = 2_000L

        // Seconds of stream mpv buffers before playing on after an underrun (default 1)
        const val CACHE_PAUSE_WAIT_SECONDS = 4

        // Same hardening as Windows; render/decode settings from mpv-android.
        val OPTIONS = listOf(
            "config" to "no",
            "load-scripts" to "no",
            "ytdl" to "no",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "terminal" to "no",
            "idle" to "once",
            "keep-open" to "yes",
            "save-position-on-quit" to "no",
            "tls-verify" to "yes",
            "vo" to VO,
            "gpu-context" to "android",
            "opengl-es" to "yes",
            "hwdec" to "mediacodec,mediacodec-copy",
            "hwdec-codecs" to "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1",
            "ao" to "audiotrack,opensles",
            "audio-set-media-role" to "yes",
            // mpv's default, stated: at another speed, scaletempo2 keeps voices at their pitch
            "audio-pitch-correction" to "yes",
            // French subtitles when neither the track menu nor the server picks one
            "slang" to "fr,fre,fra",
            // The Shield has 3 GB of RAM: a bounded demuxer cache, mpv's own default ahead.
            // 64 MiB held 8 s of a 60 Mb/s file: too little over Wi-Fi
            "demuxer-max-bytes" to "${150 * 1024 * 1024}",
            "demuxer-max-back-bytes" to "${32 * 1024 * 1024}",
            "cache" to "yes",
            // A few seconds buffered before the first frame and after each underrun: one
            // short wait instead of a cut every second while the network barely keeps up
            "cache-pause-initial" to "yes",
            "cache-pause-wait" to "$CACHE_PAUSE_WAIT_SECONDS",
        )
    }
}

/** A source waiting for the surface, with its loadfile options. */
private class PendingFile(val source: MediaSource, val options: String, val paused: Boolean = false)
