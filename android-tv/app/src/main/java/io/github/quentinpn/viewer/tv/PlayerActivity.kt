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
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ExternalSubtitle
import io.github.quentinpn.viewer.tv.emby.PlayMethod
import io.github.quentinpn.viewer.tv.emby.Segment
import io.github.quentinpn.viewer.tv.emby.ServerTracks
import io.github.quentinpn.viewer.tv.emby.deviceProfile
import io.github.quentinpn.viewer.tv.emby.embyMarkers
import io.github.quentinpn.viewer.tv.emby.nextEpisode
import io.github.quentinpn.viewer.tv.emby.plan
import io.github.quentinpn.viewer.tv.emby.segmentQuery
import io.github.quentinpn.viewer.tv.emby.startTicks
import io.github.quentinpn.viewer.tv.player.MediaSource
import io.github.quentinpn.viewer.tv.player.MpvPlayerEngine
import io.github.quentinpn.viewer.tv.player.PlaybackFacts
import io.github.quentinpn.viewer.tv.player.PlayerEngine
import io.github.quentinpn.viewer.tv.player.PlayerListener
import io.github.quentinpn.viewer.tv.player.SkipPlan
import io.github.quentinpn.viewer.tv.player.SkipPrompt
import io.github.quentinpn.viewer.tv.player.TrackPreference
import io.github.quentinpn.viewer.tv.player.TrackPreferenceStore
import io.github.quentinpn.viewer.tv.player.TrackType
import io.github.quentinpn.viewer.tv.player.chapters
import io.github.quentinpn.viewer.tv.player.decide
import io.github.quentinpn.viewer.tv.player.describeTrack
import io.github.quentinpn.viewer.tv.player.playbackInfo
import io.github.quentinpn.viewer.tv.player.subtitlePosition
import io.github.quentinpn.viewer.tv.player.transcodeReasons
import io.github.quentinpn.viewer.tv.settings.PlaybackSettings
import io.github.quentinpn.viewer.tv.settings.PlaybackSettingsStore
import io.github.quentinpn.viewer.tv.settings.mbits
import io.github.quentinpn.viewer.tv.ui.PlayerOverlay
import io.github.quentinpn.viewer.tv.ui.PlayerUi
import io.github.quentinpn.viewer.tv.ui.UpNext
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import io.github.quentinpn.viewer.tv.ui.episodeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "ViewerTV"
private const val TICKS_PER_SECOND = 10_000_000.0
private const val SEEK_SECONDS = 10.0

/** As the tracker (ADR 0012): an end further than this from the duration is a cut stream. */
private const val NATURAL_END_MARGIN_SECONDS = 30.0

/** "Informations de lecture" is read again this often while it is open (ADR 0061). */
private const val INFO_REFRESH_MS = 1_000L

/**
 * Plays one Emby item. Not exported: it negotiates the stream itself through
 * PlaybackInfo, so the token never travels in an Intent (ADR 0007, 0038).
 * Start, progress and stop go to the server through [PlaybackReporter] (ADR 0040).
 * The last audio and subtitle picked in the menu apply to the next files (ADR 0042);
 * without one, the tracks the server picked from the account's preferences (ADR 0055).
 * At the natural end of an episode, the next one is offered and plays in the same
 * engine (ADR 0043). Intros and credits get a skip button, and the next episode is
 * offered from the start of its credits (ADR 0048). The Réglages apply from the next
 * item played: external sources, automatic skip and the streaming cap (ADR 0051),
 * and the look of subtitles (ADR 0053). UP lists the item's chapters, if it has some (ADR 0057).
 * "Informations de lecture" opens from the last row of the track menu, or the INFO key (ADR 0061).
 */
class PlayerActivity : ComponentActivity(), SurfaceHolder.Callback, PlayerListener {
    private var engine: PlayerEngine? = null
    private val ui = PlayerUi()
    private var subtitles: List<ExternalSubtitle> = emptyList()

    /** The server's track picks for the file being loaded, and whether it is a transcode (ADR 0055). */
    private var serverTracks = ServerTracks()
    private var transcode = false

    /** How the server delivers the file being loaded, for "Informations de lecture" (ADR 0061). */
    private var facts: PlaybackFacts? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var preferences: TrackPreferenceStore? = null
    private var settingsStore: PlaybackSettingsStore? = null

    /** Read again for each item, so a change in Réglages applies to the next one. */
    private var settings = PlaybackSettings()
    private lateinit var client: EmbyClient
    private lateinit var userId: String

    /** Item being played: an answer for another one is stale. */
    private var current: String? = null

    /** The episode after the current one, once known. */
    private var next: BaseItemDto? = null

    /** Intro and credits of the current item, once known (ADR 0048). */
    private var skips: SkipPlan? = null

    // END_FILE events of a file replaced by the next episode: not the end of playback
    @Volatile private var replacedEnds = 0
    private val reporter = AppServices.reporter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val surface = SurfaceView(this)
        val overlay = ComposeView(this).apply {
            setContent { ViewerTheme { PlayerOverlay(ui, ::selectTrack, ::goToChapter, ::playNext, ::cancelNext, ::skip, ::openInfo) } }
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
        // Back closes a menu, then the controls, then the player
        onBackPressedDispatcher.addCallback(this) {
            when {
                ui.upNext != null -> cancelNext()
                ui.menuOpen -> ui.closeMenus()
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
        client = AppServices.client(this, session)
        userId = session.userId
        preferences = TrackPreferenceStore(this, session.userId)
        settingsStore = PlaybackSettingsStore(this, session.userId)
        surface.holder.addCallback(this)

        // Subtitles move above the controls while they show, never below the place chosen in Réglages
        lifecycleScope.launch {
            snapshotFlow { ui.covered }.collect { covered ->
                engine?.setSubtitlePosition(subtitlePosition(settings.subtitles.position, covered))
            }
        }

        // While "Informations de lecture" is open, its values are read again each second
        lifecycleScope.launch {
            snapshotFlow { ui.info != null }.collectLatest { open ->
                while (open) {
                    delay(INFO_REFRESH_MS)
                    if (ui.info != null) ui.info = readInfo()
                }
            }
        }

        play(itemId, startTicks, intent.getStringExtra(EXTRA_MEDIA_SOURCE_ID))
    }

    /** [mediaSourceId]: the version chosen on the film page (ADR 0058); null lets the server pick. */
    private fun play(itemId: String, startTicks: Long, mediaSourceId: String? = null) {
        current = itemId
        next = null
        skips = null
        facts = null
        settings = settingsStore?.load() ?: PlaybackSettings()
        ui.reset()
        lifecycleScope.launch { describe(itemId) }
        lifecycleScope.launch { negotiate(itemId, startTicks, mediaSourceId) }
    }

    /** Title over the video and the next episode: playback never waits for them. */
    private suspend fun describe(itemId: String) {
        try {
            // Without Fields: also carries the chapters and provider ids for the segments
            val item = client.fullItem(userId, itemId)
            if (current != itemId) return
            ui.chapters = chapters(item.chapters, item.runTimeTicks)
            lifecycleScope.launch { findSegments(item) }
            if (item.type == "Episode") {
                ui.title = item.seriesName ?: item.name
                ui.detail = episodeLabel(item.parentIndexNumber, item.indexNumber, item.name)
                val seriesId = item.seriesId ?: return
                val following = nextEpisode(client.adjacentEpisodes(userId, seriesId, itemId), itemId, seriesId)
                if (current != itemId) return
                next = following
                Log.i(TAG, "next=${following?.id}")
            } else {
                ui.title = item.name
                ui.detail = item.productionYear?.toString()
            }
        } catch (_: EmbyException) {
        }
    }

    /**
     * Emby's markers, then IntroDB and TheIntroDB (ADR 0030): a failure only means no
     * button, and playback never waits for them.
     */
    private suspend fun findSegments(item: BaseItemDto) {
        val auto = settings.autoSkip
        // External sources turned off in Réglages: Emby's markers only, nothing leaves the app
        val found = if (!settings.externalSegments) embyMarkers(item.chapters) else externalSegments(item) ?: return
        if (current != item.id) return
        Log.i(TAG, "segments=${found.joinToString { "${it.kind}@${it.start}-${it.end ?: "end"} (${it.source})" }} auto=$auto")
        skips = SkipPlan(found, auto)
    }

    /** Emby's markers completed by IntroDB and TheIntroDB; null when another item started meanwhile. */
    private suspend fun externalSegments(item: BaseItemDto): List<Segment>? {
        val sources = AppServices.segments(this)
        val key = "${client.server.url}|$userId|${item.id}"
        return sources.cached(key) ?: run {
            val series = item.seriesId?.takeIf { item.type == "Episode" }?.let { seriesId ->
                try {
                    client.fullItem(userId, seriesId)
                } catch (_: EmbyException) {
                    // Only needed for its ids: no query, no error
                    null
                }
            }
            if (current != item.id) return null
            sources.lookup(key, embyMarkers(item.chapters), segmentQuery(item, series))
        }
    }

    private suspend fun negotiate(itemId: String, startTicks: Long, mediaSourceId: String?) {
        try {
            // The cap of the server's kind of address, else the default limit (ADR 0037, 0051)
            val bitrate = settings.maxStreamingBitrate(client.server.isLocal)
            val chosen = plan(client.server, client.playbackInfo(userId, itemId, deviceProfile(bitrate), mediaSourceId), mediaSourceId)
            if (current != itemId) return
            Log.i(TAG, "play method=${chosen.method} source=${chosen.mediaSourceId} subtitles=${chosen.subtitles.size} maxBitrate=$bitrate")
            Log.i(TAG, "subtitle style=${settings.subtitles}")
            // The server's subtitle goes last, added selected; the others are added without (ADR 0055)
            subtitles = chosen.subtitles.sortedBy { it.selected }
            serverTracks = chosen.serverTracks
            transcode = chosen.method == PlayMethod.Transcode
            // Only the reasons are kept from the URL: it is never shown
            val local = client.server.isLocal
            facts = PlaybackFacts(
                method = chosen.method,
                reasons = if (transcode) transcodeReasons(chosen.url) else emptyList(),
                cap = (settings.capFor(local)?.let(::mbits) ?: "Maximale") + if (local) " · réseau local" else " · accès distant",
            )
            val source = MediaSource.parse(chosen.url) ?: throw EmbyException(EmbyError.InvalidResponse("adresse de flux refusée"))
            // Reported once the file is loaded; never if loading fails
            reporter.begin(
                client,
                ActivePlayback(itemId, chosen.mediaSourceId, chosen.playSessionId, chosen.method, chosen.runTimeTicks, startTicks),
            )
            val running = engine
            if (running != null) {
                // Headers are fixed at mpv init (token out of logcat): the first file's apply
                replacedEnds++
                styleSubtitles(running)
                running.load(source, startTicks / TICKS_PER_SECOND)
                return
            }
            // Created once the headers are known: they can only be set before mpv init
            val player = MpvPlayerEngine(this, chosen.headers + client.streamHeaders())
            player.addListener(this)
            engine = player
            surfaceHolder?.let { holder ->
                player.attachSurface(holder.surface)
                holder.surfaceFrame.let { player.resizeSurface(it.width(), it.height()) }
            }
            styleSubtitles(player)
            player.load(source, startTicks / TICKS_PER_SECOND)
        } catch (e: EmbyException) {
            ui.status = e.error.message
        }
    }

    /** The subtitle look of Réglages for this item, above the controls if they show (ADR 0053). */
    private fun styleSubtitles(player: PlayerEngine) {
        player.setSubtitleStyle(settings.subtitles)
        player.setSubtitlePosition(subtitlePosition(settings.subtitles.position, ui.covered))
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
        subtitles.forEach { sub -> MediaSource.parse(sub.url)?.let { engine?.addSubtitle(it, sub.selected) } }
        applyTracks()
    }

    /**
     * After the external subtitles: one of them may be the remembered choice. The
     * remembered choice, else the server's pick, else mpv's (ADR 0042, 0055).
     */
    private fun applyTracks() {
        val player = engine ?: return
        val store = preferences ?: return
        val tracks = player.tracks()
        val described = TrackType.entries.map { type ->
            val server = if (type == TrackType.Audio) serverTracks.audio else serverTracks.subtitle
            val decision = decide(tracks, type, store.load(type), server, transcode)
            decision.selection?.let { player.selectTrack(type, it.id) }
            "${type.mpv}=${describeTrack(decision, player.property(type.property))}"
        }
        Log.i(TAG, "tracks ${described.joinToString(" ")}")
    }

    override fun onPosition(seconds: Double) {
        reporter.event { position(seconds, it) }
        runOnUiThread {
            ui.position = seconds
            updateSkip()
        }
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
        runOnUiThread {
            val following = next
            // A cut stream also reaches its end: no next episode then
            val natural = ui.duration <= 0 || ui.duration - ui.position <= NATURAL_END_MARGIN_SECONDS
            if (following != null && natural && ui.upNext == null) {
                ui.closeMenus()
                ui.upNext = UpNext(following.seriesName ?: ui.title, episodeLabel(following.parentIndexNumber, following.indexNumber, following.name))
            } else if (ui.upNext == null) {
                finish()
            }
        }
    }

    override fun onEndOfFile() {
        if (replacedEnds > 0) {
            replacedEnds--
            Log.i(TAG, "end of the replaced file")
            return
        }
        reporter.event { ended(it) }
        runOnUiThread { finish() }
    }

    /** The skip button, or the up-next card from the start of the credits. */
    private fun updateSkip() {
        // Until the new file loads, positions are still those of the replaced one
        val prompt = if (ui.status == null) skips?.at(ui.position, ui.duration, next != null) else null
        if (prompt == SkipPrompt.UpNext && ui.upNext == null) {
            val following = next ?: return
            ui.closeMenus()
            ui.upNext = UpNext(following.seriesName ?: ui.title, episodeLabel(following.parentIndexNumber, following.indexNumber, following.name), atCredits = true)
            Log.i(TAG, "up next at the credits")
        }
        if (prompt is SkipPrompt.Auto) {
            // Not while a menu or card is open: the button shows instead, as after a seek back
            if (!ui.menuOpen && ui.upNext == null) {
                Log.i(TAG, "auto skip")
                seekPast(prompt.to)
                return
            }
        }
        ui.skip = prompt as? SkipPrompt.Button
    }

    /** Seeks past the segment; past the end of the file, mpv ends it as a natural end. */
    private fun skip() {
        val target = ui.skip?.to ?: return
        if (seekPast(target)) ui.show()
    }

    private fun seekPast(target: Double): Boolean {
        val player = engine ?: return false
        Log.i(TAG, "skip to $target")
        player.seekTo(target)
        ui.position = target
        ui.skip = null
        return true
    }

    /** At the credits, the card goes and the credits play on; at the end, the player closes. */
    private fun cancelNext() {
        if (ui.upNext?.atCredits == true) ui.upNext = null else finish()
    }

    private fun playNext() {
        val following = next ?: return finish()
        Log.i(TAG, "play next=${following.id}")
        play(following.id, following.startTicks())
    }

    /**
     * The remote drives the player itself; only an open menu hands its keys
     * to Compose, for focus. Back goes through the dispatcher callback.
     */
    // Nothing focused (menu closed): keys come here instead of through the root view
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyUp(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val player = engine ?: return false
        if (ui.status != null || ui.menuOpen || ui.upNext != null) return false
        // OK presses the skip button while it shows; play / pause keys still pause
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && ui.skip != null) {
            if (event.action == KeyEvent.ACTION_DOWN) skip()
            return true
        }
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
        KeyEvent.KEYCODE_DPAD_UP -> { _ ->
            ui.chapterMenu = ui.chapters.isNotEmpty()
            ui.show()
        }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { p ->
            ui.tracks = p.tracks()
            ui.show()
        }
        // Remotes that have it; the Shield's has not, and goes through the track menu
        KeyEvent.KEYCODE_INFO -> { _ -> openInfo() }
        else -> null
    }

    private fun seek(player: PlayerEngine, seconds: Double) {
        player.seekBy(seconds)
        // Shown at once; mpv's next position replaces it
        val end = if (ui.duration > 0) ui.duration else Double.MAX_VALUE
        ui.position = (ui.position + seconds).coerceIn(0.0, end)
        ui.show()
    }

    /** A row of the chapter menu: playback goes to its start and the menu closes. */
    private fun goToChapter(start: Double) {
        val player = engine ?: return
        Log.i(TAG, "chapter at $start")
        player.seekTo(start)
        ui.position = start
        ui.chapterMenu = false
        ui.show()
    }

    /** "Informations de lecture" in place of the track menu (ADR 0061). */
    private fun openInfo() {
        val rows = readInfo() ?: return
        ui.tracks = null
        ui.chapterMenu = false
        ui.info = rows
        ui.show()
    }

    /** The panel's rows for the file being played; null before its negotiation or without a player. */
    private fun readInfo() = engine?.let { player ->
        facts?.let { playbackInfo(it, player.tracks(), player::property) }
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
        const val EXTRA_MEDIA_SOURCE_ID = "mediaSourceId"
    }
}
