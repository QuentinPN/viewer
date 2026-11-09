package io.github.quentinpn.viewer.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.quentinpn.viewer.tv.player.MediaSource
import io.github.quentinpn.viewer.tv.player.MpvPlayerEngine
import io.github.quentinpn.viewer.tv.player.PlayerEngine
import io.github.quentinpn.viewer.tv.player.PlayerListener

private const val TAG = "ViewerTV"
private const val DIAG_INTERVAL_MS = 2000L

/** Diagnostics logged every [DIAG_INTERVAL_MS] and shown in the diagView (Gate TV-1 evidence). */
private val DIAG_PROPERTIES = listOf(
    "time-pos", "duration", "pause", "paused-for-cache",
    "current-vo", "current-ao", "hwdec-current",
    "video-codec", "video-params/w", "video-params/h", "video-params/pixelformat",
    "video-params/gamma", "video-params/primaries", "container-fps", "estimated-vf-fps",
    "audio-codec-name", "audio-params/channel-count", "aid", "sid",
    "current-tracks/sub/codec", "current-tracks/sub/lang", "sub-text",
    "frame-drop-count", "decoder-frame-drop-count",
)

/**
 * Gate TV-1 proof of concept: plays one source given by `adb shell am start`
 * extras, driven by the remote. Not the final player UI (ADR 0038).
 *
 * Extras: `url` (absolute path or http(s)), optional `sub` (external subtitle),
 * `token` (sent as X-Emby-Token, never in the URL) and `start` (seconds).
 */
class PlaybackPocActivity : Activity(), SurfaceHolder.Callback, PlayerListener {
    private val ui = Handler(Looper.getMainLooper())
    private var engine: PlayerEngine? = null
    private lateinit var diagView: TextView
    private var pendingSubtitle: MediaSource? = null

    private val diagTick = object : Runnable {
        override fun run() {
            val line = DIAG_PROPERTIES.joinToString(" ") { "$it=${engine?.property(it)}" }
            Log.i(TAG, "diag $line")
            diagView.text = DIAG_PROPERTIES.joinToString("\n") { "$it : ${engine?.property(it) ?: "—"}" }
            ui.postDelayed(this, DIAG_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val surface = SurfaceView(this)
        diagView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 16, 24, 16)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(surface)
                val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
                addView(diagView, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.START))
            },
        )

        val source = intent.getStringExtra("url")?.let(MediaSource::parse)
        if (source == null) {
            diagView.text = "Aucune source valide.\nadb shell am start -n io.github.quentinpn.viewer.tv/.PlaybackPocActivity --es url <chemin|http(s)>"
            return
        }
        pendingSubtitle = intent.getStringExtra("sub")?.let(MediaSource::parse)
        val headers = intent.getStringExtra("token")?.let { mapOf("X-Emby-Token" to it) } ?: emptyMap()
        val start = intent.getStringExtra("start")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0

        val started = SystemClock.elapsedRealtime()
        engine = MpvPlayerEngine(this).also {
            it.addListener(this)
            it.load(source, headers, start)
        }
        Log.i(TAG, "engine created in ${SystemClock.elapsedRealtime() - started} ms, source=${source::class.simpleName}")
        surface.holder.addCallback(this)
        ui.postDelayed(diagTick, DIAG_INTERVAL_MS)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        engine?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surface ${width}x$height")
        engine?.resizeSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.detachSurface()
    }

    override fun onFileLoaded() {
        Log.i(TAG, "file loaded")
        pendingSubtitle?.let { sub ->
            pendingSubtitle = null
            engine?.addSubtitle(sub)
        }
    }

    override fun onEndOfFile() {
        Log.i(TAG, "end of file")
    }

    override fun onPauseChanged(paused: Boolean) {
        Log.i(TAG, "pause=$paused")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val player = engine ?: return super.onKeyDown(keyCode, event)
        Log.i(TAG, "key ${KeyEvent.keyCodeToString(keyCode)}")
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> player.togglePause()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBy(-10.0)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekBy(10.0)
            KeyEvent.KEYCODE_DPAD_UP -> player.cycleSubtitle()
            KeyEvent.KEYCODE_DPAD_DOWN -> player.cycleAudio()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> diagView.visibility =
                if (diagView.visibility == TextView.VISIBLE) TextView.GONE else TextView.VISIBLE
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onDestroy() {
        ui.removeCallbacks(diagTick)
        engine?.let {
            val started = SystemClock.elapsedRealtime()
            it.release()
            Log.i(TAG, "teardown ${SystemClock.elapsedRealtime() - started} ms")
        }
        engine = null
        super.onDestroy()
    }
}
