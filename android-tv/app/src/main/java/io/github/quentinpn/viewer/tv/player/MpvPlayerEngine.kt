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
 */
class MpvPlayerEngine(context: Context) : PlayerEngine, MPVLib.EventObserver, MPVLib.LogObserver {
    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val released = AtomicBoolean(false)
    private var surfaceAttached = false
    private var pending: MediaSource? = null

    init {
        check(alive.compareAndSet(false, true)) { "Un seul lecteur mpv peut exister à la fois" }
        MPVLib.create(context.applicationContext)
        for ((name, value) in OPTIONS) {
            val rc = MPVLib.setOptionString(name, value)
            if (rc < 0) Log.w(TAG, "option $name=$value refusée ($rc)")
        }
        MPVLib.init()
        // Must stay off until a surface exists, or VO init fails (mpv-android BaseMPVView)
        MPVLib.setOptionString("force-window", "no")
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        MPVLib.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
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
        val source = pending
        if (source != null) {
            pending = null
            MPVLib.command(arrayOf("loadfile", source.uri))
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

    override fun load(source: MediaSource, headers: Map<String, String>, startSeconds: Double) {
        if (released.get()) return
        MPVLib.setPropertyString("start", if (startSeconds > 0) startSeconds.toString() else "none")
        // A comma would split the list: such a header is dropped rather than mangled
        val fields = headers.filterValues { ',' !in it }.map { (k, v) -> "$k: $v" }
        MPVLib.setPropertyString("http-header-fields", fields.joinToString(","))
        if (surfaceAttached) MPVLib.command(arrayOf("loadfile", source.uri)) else pending = source
    }

    override fun addSubtitle(source: MediaSource) {
        if (released.get()) return
        MPVLib.command(arrayOf("sub-add", source.uri, "select"))
    }

    override fun togglePause() = command("cycle", "pause")

    override fun seekBy(seconds: Double) = command("seek", seconds.toString(), "relative")

    override fun cycleSubtitle() = command("cycle", "sub")

    override fun cycleAudio() = command("cycle", "audio")

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
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") listeners.forEach { it.onPauseChanged(value) }
    }

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: Long) {}

    override fun eventProperty(property: String, value: String) {}

    override fun eventProperty(property: String, value: Double) {}

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
            // French subtitles by default; user preferences come with the real player UI
            "slang" to "fr,fre,fra",
            // The Shield has 3 GB of RAM: a bounded demuxer cache
            "demuxer-max-bytes" to "${64 * 1024 * 1024}",
            "demuxer-max-back-bytes" to "${32 * 1024 * 1024}",
        )
    }
}
