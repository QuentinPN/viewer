package io.github.quentinpn.viewer.tv

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
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
import io.github.quentinpn.viewer.tv.player.TrackPreference
import io.github.quentinpn.viewer.tv.player.TrackPreferenceStore
import io.github.quentinpn.viewer.tv.player.TrackType
import io.github.quentinpn.viewer.tv.player.choose
import io.github.quentinpn.viewer.tv.ui.PlayerOverlay
import io.github.quentinpn.viewer.tv.ui.PlayerUi
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "ViewerTV"
private const val TICKS_PER_SECOND = 10_000_000.0
private const val SEEK_SECONDS = 10.0

/**
 * Plays one Emby item. Not exported: it negotiates the stream itself through
 * PlaybackInfo, so the token never travels in an Intent (ADR 0007, 0038).
 * Start, progress and stop go to the server through [PlaybackReporter] (ADR 0040).
 * The last audio and subtitle picked in the menu apply to the next files (ADR 0042).
 */
class PlayerActivity : ComponentActivity(), SurfaceHolder.Callback, PlayerListener {
    private var engine: PlayerEngine? = null
    private val ui = PlayerUi()
    private var subtitles: List<ExternalSubtitle> = emptyList()
    private var surfaceHolder: SurfaceHolder? = null
    private var preferences: TrackPreferenceStore? = null
    private val reporter = AppServices.reporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val surface = SurfaceView(this)
        val overlay = ComposeView(this).apply {
            setContent { ViewerTheme { PlayerOverlay(ui, ::selectTrack) } }
        }
        setContentView(
            // With the menu focused, keys reach the root before the overlay: the player sees them first
            object : FrameLayout(this) {
                override fun dispatchKeyEvent(event: KeyEvent) = handleKey(event) || super.dispatchKeyEvent(event)
            }.apply {
                setBackgroundColor(Color.BLACK)
                addView(surface)
                addView(overlay)
            },
        )
        // Back closes the track menu, then the controls, then the player
        onBackPressedDispatcher.addCallback(this) {
            when {
                ui.tracks != null -> ui.tracks = null
                ui.controls && !ui.paused -> ui.hide()
                else -> finish()
            }
        }

        val session = AppServices.store(this).load()
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        if (session == null || itemId == null) {
            finish()
            return
        }
        val startTicks = intent.getLongExtra(EXTRA_START_TICKS, 0).coerceAtLeast(0)
        val startSeconds = startTicks / TICKS_PER_SECOND
        val client = AppServices.client(this, session)
        preferences = TrackPreferenceStore(this, session.userId)
        surface.holder.addCallback(this)

        // Subtitles move above the controls while they show
        lifecycleScope.launch {
            snapshotFlow { ui.covered }.collect { covered ->
                engine?.setSubtitlePosition((100 * (1 - covered)).roundToInt())
            }
        }

        // Only for the title over the video: playback does not wait for it
        lifecycleScope.launch {
            try {
                val item = client.item(session.userId, itemId)
                if (item.type == "Episode") {
                    ui.title = item.seriesName ?: item.name
                    ui.detail = listOfNotNull(
                        item.parentIndexNumber?.let { "S$it" },
                        item.indexNumber?.let { "É$it" },
                    ).joinToString(" · ").let { if (it.isEmpty()) item.name else "$it — ${item.name}" }
                } else {
                    ui.title = item.name
                    ui.detail = item.productionYear?.toString()
                }
            } catch (_: EmbyException) {
            }
        }
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
                ui.status = e.error.message
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
        runOnUiThread {
            ui.status = null
            ui.show()
        }
        subtitles.forEach { sub -> MediaSource.parse(sub.url)?.let { engine?.addSubtitle(it) } }
        applyPreferences()
    }

    /** After the external subtitles: one of them may be the remembered choice. */
    private fun applyPreferences() {
        val player = engine ?: return
        val store = preferences ?: return
        val tracks = player.tracks()
        for (type in TrackType.entries) {
            val selection = choose(tracks, type, store.load(type)) ?: continue
            player.selectTrack(type, selection.id)
            Log.i(TAG, "${type.property}=${player.property(type.property)} (préférence)")
        }
    }

    override fun onPosition(seconds: Double) {
        reporter.event { position(seconds, it) }
        runOnUiThread { ui.position = seconds }
    }

    override fun onDuration(seconds: Double) {
        reporter.event { duration(seconds); emptyList() }
        runOnUiThread { ui.duration = seconds }
    }

    override fun onPauseChanged(paused: Boolean) {
        reporter.event { paused(paused, it) }
        runOnUiThread { ui.paused = paused }
    }

    override fun onSeek() = reporter.event { seeked(); emptyList() }

    override fun onEndReached() {
        reporter.event { ended(it) }
        runOnUiThread { finish() }
    }

    override fun onEndOfFile() {
        reporter.event { ended(it) }
        runOnUiThread { finish() }
    }

    /**
     * The remote drives the player itself; only the open track menu hands its keys
     * to Compose, for focus. Back goes through the dispatcher callback.
     */
    // Nothing focused (menu closed): keys come here instead of through the root view
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyUp(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val player = engine ?: return false
        if (ui.status != null || ui.tracks != null) return false
        val action = action(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) action(player)
        return true
    }

    private fun action(keyCode: Int): ((PlayerEngine) -> Unit)? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { p ->
            p.togglePause()
            ui.show()
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p -> seek(p, -SEEK_SECONDS) }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p -> seek(p, SEEK_SECONDS) }
        KeyEvent.KEYCODE_DPAD_UP -> { _ -> ui.show() }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { p ->
            ui.tracks = p.tracks()
            ui.show()
        }
        else -> null
    }

    private fun seek(player: PlayerEngine, seconds: Double) {
        player.seekBy(seconds)
        // Shown at once; mpv's next position replaces it
        val end = if (ui.duration > 0) ui.duration else Double.MAX_VALUE
        ui.position = (ui.position + seconds).coerceIn(0.0, end)
        ui.show()
    }

    private fun selectTrack(type: TrackType, id: Int?) {
        val player = engine ?: return
        val track = ui.tracks?.find { it.type == type && it.id == id }
        preferences?.save(type, track?.let(TrackPreference::of) ?: TrackPreference.OFF)
        player.selectTrack(type, id)
        Log.i(TAG, "${type.property}=${player.property(type.property)}")
        ui.tracks = player.tracks()
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
