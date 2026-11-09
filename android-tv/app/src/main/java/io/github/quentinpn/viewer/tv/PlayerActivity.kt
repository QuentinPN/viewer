package io.github.quentinpn.viewer.tv

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.quentinpn.viewer.tv.emby.ActivePlayback
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ExternalSubtitle
import io.github.quentinpn.viewer.tv.emby.deviceProfile
import io.github.quentinpn.viewer.tv.emby.plan
import io.github.quentinpn.viewer.tv.player.MediaSource
import io.github.quentinpn.viewer.tv.player.MpvPlayerEngine
import io.github.quentinpn.viewer.tv.player.PlayerEngine
import io.github.quentinpn.viewer.tv.player.PlayerListener
import kotlinx.coroutines.launch

private const val TAG = "ViewerTV"
private const val TICKS_PER_SECOND = 10_000_000.0

/**
 * Plays one Emby item. Not exported: it negotiates the stream itself through
 * PlaybackInfo, so the token never travels in an Intent (ADR 0007, 0038).
 * Start, progress and stop go to the server through [PlaybackReporter] (ADR 0040).
 */
class PlayerActivity : ComponentActivity(), SurfaceHolder.Callback, PlayerListener {
    private var engine: PlayerEngine? = null
    private lateinit var status: TextView
    private var subtitles: List<ExternalSubtitle> = emptyList()
    private var surfaceHolder: SurfaceHolder? = null
    private val reporter = AppServices.reporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val surface = SurfaceView(this)
        status = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            text = "Chargement…"
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(surface)
                val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
                addView(status, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
            },
        )

        val session = AppServices.store(this).load()
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        if (session == null || itemId == null) {
            finish()
            return
        }
        val startTicks = intent.getLongExtra(EXTRA_START_TICKS, 0).coerceAtLeast(0)
        val startSeconds = startTicks / TICKS_PER_SECOND
        val client = AppServices.client(this, session)
        surface.holder.addCallback(this)

        lifecycleScope.launch {
            try {
                val chosen = plan(client.server, client.playbackInfo(session.userId, itemId, deviceProfile()))
                Log.i(TAG, "play method=${chosen.method} source=${chosen.mediaSourceId} subtitles=${chosen.subtitles.size}")
                // The selected subtitle goes last: each sub-add selects its track
                subtitles = chosen.subtitles.sortedBy { it.selected }
                val source = MediaSource.parse(chosen.url) ?: throw EmbyException(EmbyError.InvalidResponse("adresse de flux refusée"))
                // Reported once the file is loaded; never if loading fails
                reporter.begin(
                    client,
                    ActivePlayback(itemId, chosen.mediaSourceId, chosen.playSessionId, chosen.method, chosen.runTimeTicks, startTicks),
                )
                // Created once the headers are known: they can only be set before mpv init
                val player = MpvPlayerEngine(this@PlayerActivity, chosen.headers + client.streamHeaders())
                player.addListener(this@PlayerActivity)
                engine = player
                surfaceHolder?.let { holder ->
                    player.attachSurface(holder.surface)
                    holder.surfaceFrame.let { player.resizeSurface(it.width(), it.height()) }
                }
                player.load(source, startSeconds)
            } catch (e: EmbyException) {
                status.text = e.error.message
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        engine?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine?.resizeSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
        engine?.detachSurface()
    }

    override fun onFileLoaded() {
        reporter.event { fileLoaded(it) }
        runOnUiThread { status.visibility = View.GONE }
        subtitles.forEach { sub -> MediaSource.parse(sub.url)?.let { engine?.addSubtitle(it) } }
    }

    override fun onPosition(seconds: Double) = reporter.event { position(seconds, it) }

    override fun onDuration(seconds: Double) = reporter.event { duration(seconds); emptyList() }

    override fun onPauseChanged(paused: Boolean) = reporter.event { paused(paused, it) }

    override fun onSeek() = reporter.event { seeked(); emptyList() }

    override fun onEndReached() {
        reporter.event { ended(it) }
        runOnUiThread { finish() }
    }

    override fun onEndOfFile() {
        reporter.event { ended(it) }
        runOnUiThread { finish() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val player = engine ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> player.togglePause()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> player.seekBy(-10.0)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> player.seekBy(10.0)
            KeyEvent.KEYCODE_DPAD_UP -> {
                player.cycleSubtitle()
                Log.i(TAG, "sid=${player.property("sid")}")
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> player.cycleAudio()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        // Home button or another app: playback ends, it does not go on unseen
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        // Before release: the tracker keeps the last position mpv reported
        reporter.event { closed(it) }
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_START_TICKS = "startTicks"
    }
}
