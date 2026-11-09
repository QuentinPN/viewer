package io.github.quentinpn.viewer.tv.player

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvEvent
import `is`.xyz.mpv.MPVLib.MpvFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ViewerMpv"
private const val VO = "gpu"

/**
 * libmpv through mpv-android's JNI bridge (ADR 0038). libplayer.so keeps a
 * single global mpv handle and aborts the process on a second create, so only
 * one engine may be alive at a time.
 *
 * [headers] are set once, before `mpv_initialize`: libmpv logs every later option
 * or property change with its value at verbose level, and libplayer copies each
 * libmpv log line to logcat itself, before [redactSecrets] can see it. Messages
 * emitted before init are never delivered, so the token stays out of logcat.
 * They apply to every request of this engine, external subtitles included.
 */
class MpvPlayerEngine(context: Context, headers: Map<String, String> = emptyMap()) : PlayerEngine, MPVLib.EventObserver, MPVLib.LogObserver {
    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val released = AtomicBoolean(false)
    private var surfaceAttached = false
    private var pending: PendingFile? = null

    init {
        check(alive.compareAndSet(false, true)) { "Un seul lecteur mpv peut exister à la fois" }
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

    override fun load(source: MediaSource, startSeconds: Double) {
        if (released.get()) return
        // Per-file option: a later file does not inherit this start position
        val start = if (startSeconds > 0) startSeconds.toString() else "none"
        val file = PendingFile(source, "start=$start")
        if (surfaceAttached) loadNow(file) else pending = file
    }

    private fun loadNow(file: PendingFile) {
        // keep-open paused the previous file on its last frame
        MPVLib.setPropertyString("pause", "no")
        MPVLib.command(arrayOf("loadfile", file.source.uri, "replace", "-1", file.options))
    }

    override fun addSubtitle(source: MediaSource) {
        if (released.get()) return
        MPVLib.command(arrayOf("sub-add", source.uri, "select"))
    }

    override fun togglePause() = command("cycle", "pause")

    override fun seekBy(seconds: Double) = command("seek", seconds.toString(), "relative")

    override fun seekTo(seconds: Double) = command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute")

    override fun cycleSubtitle() = command("cycle", "sub")

    override fun cycleAudio() = command("cycle", "audio")

    override fun tracks(): List<Track> = if (released.get()) emptyList() else readTracks { MPVLib.getPropertyString(it) }

    override fun selectTrack(type: TrackType, id: Int?) {
        if (!released.get()) MPVLib.setPropertyString(type.property, id?.toString() ?: "no")
    }

    override fun setSubtitlePosition(percent: Int) {
        if (!released.get()) MPVLib.setPropertyString("sub-pos", percent.coerceIn(0, 100).toString())
    }

    override fun property(name: String): String? = if (released.get()) null else MPVLib.getPropertyString(name)

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        MPVLib.removeObserver(this)
        MPVLib.removeLogObserver(this)
        MPVLib.destroy()
        surfaceAttached = false
        alive.set(false)
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

        // Same hardening as Windows (ADR 0002, 0025); render/decode settings from mpv-android.
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
            // French subtitles by default; a choice made in the track menu then wins (ADR 0042)
            "slang" to "fr,fre,fra",
            // The Shield has 3 GB of RAM: a bounded demuxer cache
            "demuxer-max-bytes" to "${64 * 1024 * 1024}",
            "demuxer-max-back-bytes" to "${32 * 1024 * 1024}",
        )
    }
}

/** A source waiting for the surface, with its loadfile options. */
private class PendingFile(val source: MediaSource, val options: String)
