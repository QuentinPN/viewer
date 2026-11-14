package io.github.quentinpn.viewer.tv

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.quentinpn.viewer.tv.sync.Body
import io.github.quentinpn.viewer.tv.sync.CmdAction
import io.github.quentinpn.viewer.tv.sync.DECIDE_EVERY_MS
import io.github.quentinpn.viewer.tv.sync.LinkedPlayer
import io.github.quentinpn.viewer.tv.sync.SPEED_NORMAL
import io.github.quentinpn.viewer.tv.sync.linkPill
import io.github.quentinpn.viewer.tv.sync.decide as linkDecide
import io.github.quentinpn.viewer.tv.emby.ActivePlayback
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ExternalSubtitle
import io.github.quentinpn.viewer.tv.emby.MAX_STREAMING_BITRATE
import io.github.quentinpn.viewer.tv.emby.PlayMethod
import io.github.quentinpn.viewer.tv.emby.PlaybackPlan
import io.github.quentinpn.viewer.tv.emby.PlaybackTracker
import io.github.quentinpn.viewer.tv.emby.Report
import io.github.quentinpn.viewer.tv.emby.Segment
import io.github.quentinpn.viewer.tv.emby.ServerTracks
import io.github.quentinpn.viewer.tv.emby.deviceProfile
import io.github.quentinpn.viewer.tv.emby.embyMarkers
import io.github.quentinpn.viewer.tv.emby.nextEpisode
import io.github.quentinpn.viewer.tv.emby.plan
import io.github.quentinpn.viewer.tv.emby.segmentQuery
import io.github.quentinpn.viewer.tv.emby.startTicks
import io.github.quentinpn.viewer.tv.player.AccountTracks
import io.github.quentinpn.viewer.tv.player.DelayChange
import io.github.quentinpn.viewer.tv.player.DelayKind
import io.github.quentinpn.viewer.tv.player.Delays
import io.github.quentinpn.viewer.tv.player.FitChange
import io.github.quentinpn.viewer.tv.player.KEEPASPECT_PROPERTY
import io.github.quentinpn.viewer.tv.player.MediaSource
import io.github.quentinpn.viewer.tv.player.MpvPlayerEngine
import io.github.quentinpn.viewer.tv.player.NORMAL_SPEED
import io.github.quentinpn.viewer.tv.player.Night
import io.github.quentinpn.viewer.tv.player.NightState
import io.github.quentinpn.viewer.tv.player.PANSCAN_PROPERTY
import io.github.quentinpn.viewer.tv.player.PlaybackFacts
import io.github.quentinpn.viewer.tv.player.PlayerEngine
import io.github.quentinpn.viewer.tv.player.PlayerListener
import io.github.quentinpn.viewer.tv.player.Quality
import io.github.quentinpn.viewer.tv.player.QualityChange
import io.github.quentinpn.viewer.tv.player.SeekThumbnails
import io.github.quentinpn.viewer.tv.player.SkipPlan
import io.github.quentinpn.viewer.tv.player.SLEEP_NOTICE
import io.github.quentinpn.viewer.tv.player.SkipPrompt
import io.github.quentinpn.viewer.tv.player.SkipArm
import io.github.quentinpn.viewer.tv.player.SkipOk
import io.github.quentinpn.viewer.tv.player.trackSkipShown
import io.github.quentinpn.viewer.tv.player.SleepChange
import io.github.quentinpn.viewer.tv.player.SleepChoice
import io.github.quentinpn.viewer.tv.player.SleepItem
import io.github.quentinpn.viewer.tv.player.SleepTimer
import io.github.quentinpn.viewer.tv.player.SpeedChange
import io.github.quentinpn.viewer.tv.player.TrackPreference
import io.github.quentinpn.viewer.tv.player.TrackPreferenceStore
import io.github.quentinpn.viewer.tv.player.Track
import io.github.quentinpn.viewer.tv.player.TrackType
import io.github.quentinpn.viewer.tv.player.VideoFit
import io.github.quentinpn.viewer.tv.player.accountPick
import io.github.quentinpn.viewer.tv.player.applied
import io.github.quentinpn.viewer.tv.player.applying
import io.github.quentinpn.viewer.tv.player.capFact
import io.github.quentinpn.viewer.tv.player.changeDelay
import io.github.quentinpn.viewer.tv.player.changeFit
import io.github.quentinpn.viewer.tv.player.changeSleep
import io.github.quentinpn.viewer.tv.player.changeSpeed
import io.github.quentinpn.viewer.tv.player.chapters
import io.github.quentinpn.viewer.tv.player.decide
import io.github.quentinpn.viewer.tv.player.localPick
import io.github.quentinpn.viewer.tv.player.describeTrack
import io.github.quentinpn.viewer.tv.player.downloadThumbnails
import io.github.quentinpn.viewer.tv.player.failed
import io.github.quentinpn.viewer.tv.player.hasStartParameter
import io.github.quentinpn.viewer.tv.player.keepsAudioRequest
import io.github.quentinpn.viewer.tv.player.playbackInfo
import io.github.quentinpn.viewer.tv.player.playingAudioIndex
import io.github.quentinpn.viewer.tv.player.playingAudioLanguage
import io.github.quentinpn.viewer.tv.player.rememberedAudioIndex
import io.github.quentinpn.viewer.tv.player.seekStep
import io.github.quentinpn.viewer.tv.player.seeksOnRepeat
import io.github.quentinpn.viewer.tv.player.sleepDue
import io.github.quentinpn.viewer.tv.player.sleepTimer
import io.github.quentinpn.viewer.tv.player.step
import io.github.quentinpn.viewer.tv.player.subtitlePosition
import io.github.quentinpn.viewer.tv.player.thumbnailsLog
import io.github.quentinpn.viewer.tv.player.transcodeReasons
import io.github.quentinpn.viewer.tv.settings.PlaybackSettings
import io.github.quentinpn.viewer.tv.settings.PlaybackSettingsStore
import io.github.quentinpn.viewer.tv.ui.PlayerOverlay
import io.github.quentinpn.viewer.tv.ui.PlayerUi
import io.github.quentinpn.viewer.tv.ui.SeekFrame
import io.github.quentinpn.viewer.tv.ui.UpNext
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import io.github.quentinpn.viewer.tv.ui.episodeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private const val TAG = "ViewerTV"
private const val TICKS_PER_SECOND = 10_000_000.0
private const val TICKS_PER_MS = 10_000L

/** Read ahead before a linked load answers `ready` (ADR 0124). */
private const val READY_CACHE_SECONDS = 3.0

/** A link message over the picture goes after this long. */
private const val LINK_NOTICE_MS = 6_000L

/** As the tracker (ADR 0012): an end further than this from the duration is a cut stream. */
private const val NATURAL_END_MARGIN_SECONDS = 30.0

/** "Informations de lecture" is read again this often while it is open (ADR 0061). */
private const val INFO_REFRESH_MS = 1_000L

/**
 * Plays one Emby item. Not exported: it negotiates the stream itself through
 * PlaybackInfo, so the token never travels in an Intent (ADR 0007, 0038).
 * Start, progress and stop go to the server through [PlaybackReporter] (ADR 0040).
 * The last audio and subtitle picked in the menu apply to the next files (ADR 0042);
 * without one, the tracks the server picked from the account's preferences (ADR 0055),
 * else those preferences applied by the app (ADR 0065).
 * At the natural end of an episode, the next one is offered and plays in the same
 * engine (ADR 0043). Intros and credits get a skip button, and the next episode is
 * offered from the start of its credits (ADR 0048). The Réglages apply from the next
 * item played: external sources, automatic skip and the streaming cap (ADR 0051),
 * and the look of subtitles (ADR 0053). UP lists the item's chapters, if it has some (ADR 0057).
 * "Informations de lecture" opens from the last row of the track menu, or the INFO key (ADR 0061).
 * Subtitles and audio can be shifted from the track menu, for the current file only (ADR 0062).
 * ◀ ▶ show the server's thumbnail of the target above the bar, when it has extracted some (ADR 0069).
 * The track menu also sets the speed, for the current file only, and night mode, kept in Réglages (ADR 0072).
 * The controls show the time and when playback ends; the track menu's « Minuterie » pauses
 * playback after a while, or at the credits, for this player session only (ADR 0073).
 * Its « Format » crops or stretches the picture to the screen, for the current file only (ADR 0078).
 * Its « Qualité » asks the server again at another cap and reloads at the same place, for this playback only (ADR 0081).
 * A local trailer ([EXTRA_TRAILER]) plays without any report, segment lookup or next episode (ADR 0083).
 */
class PlayerActivity : ComponentActivity(), SurfaceHolder.Callback, PlayerListener, LinkedPlayer {
    private var engine: PlayerEngine? = null
    private val ui = PlayerUi()
    private var subtitles: List<ExternalSubtitle> = emptyList()

    /** The server's track picks for the file being loaded, and whether it is a transcode (ADR 0055). */
    private var serverTracks = ServerTracks()
    private var transcode = false

    /** How the server delivers the file being loaded, for "Informations de lecture" (ADR 0061). */
    private var facts: PlaybackFacts? = null

    /** The stream negotiated for the file being loaded or played: its version and session (ADR 0081). */
    private var stream: PlaybackPlan? = null

    /** Speed, delays and format to put back once the file of a quality change loads (ADR 0081). */
    @Volatile private var restore: Adjustments? = null

    /** Play session whose transcode ends once the file of a quality change loads (ADR 0081). */
    @Volatile private var replacedEncoding: String? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var preferences: TrackPreferenceStore? = null

    /** The account's track preferences once read (ADR 0065); playback never waits for them. */
    @Volatile private var account: AccountTracks? = null
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

    /** The same segments, for « Fin de l'épisode » of the sleep timer (ADR 0073). */
    private var segments: List<Segment> = emptyList()

    /** Seek thumbnails of the file being played, fetched at its first seek (ADR 0069). */
    private var thumbnails: SeekThumbnails? = null

    /** Lookup and decoding of the thumbnail at the last seek target; a newer seek replaces it. */
    private var previewJob: Job? = null

    // END_FILE events of a file replaced by the next episode: not the end of playback
    @Volatile private var replacedEnds = 0

    /** External subtitles of the loaded file being added, off mpv's event thread (ADR 0085). */
    @Volatile private var subtitleJob: Job? = null
    private val reporter by lazy { AppServices.reporter(this) }

    /** A trailer (ADR 0083): nothing reported to the server, no segments, no next episode. */
    private var trailer = false

    private val hub by lazy { AppServices.sync(this) }

    /** The linked `load` being played: its tracks, and `ready` still to send until [readyJob] ends (ADR 0124). */
    private var linkLoad: Body.Load? = null
    private var readyJob: Job? = null

    /** The speed the link set; away from 1 while it corrects. */
    private var linkSpeed = SPEED_NORMAL

    // Written on mpv's event thread
    @Volatile private var lastSeekAt = 0L

    private var evaluating: Job? = null

    /** A held ◀ ▶ moved playback: its final position is sent when the key is released. */
    private var heldMoved = false

    /** A player event for the tracker, unless a trailer plays: it never reaches the server. */
    private fun report(apply: PlaybackTracker.(now: Long) -> List<Report>) {
        if (!trailer) reporter.event(apply)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val surface = SurfaceView(this)
        val overlay = ComposeView(this).apply {
            setContent { ViewerTheme { PlayerOverlay(ui, ::selectTrack, ::goToChapter, ::playNext, ::cancelNext, ::skip, ::openInfo, ::shiftDelay, ::shiftSpeed, ::shiftFit, ::toggleNight, ::changeTimer, ::pickQuality, ::applyQuality, ::linkAction) } }
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
        trailer = intent.getBooleanExtra(EXTRA_TRAILER, false)
        client = AppServices.client(this, session)
        userId = session.userId
        preferences = TrackPreferenceStore(this, session.userId)
        settingsStore = PlaybackSettingsStore(this, session.userId)
        surface.holder.addCallback(this)
        if (!trailer) {
            hub.attach(this)
            // The session's socket stays open while the player shows (ADR 0123)
            lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { hub.hold(client, userId) } }
            lifecycleScope.launch {
                hub.view.collect { view ->
                    ui.linkBusy = view.busy
                    ui.linkPill = view.device?.takeIf { view.linked }?.let { linkPill(it, view.driftMs) }
                }
            }
            lifecycleScope.launch {
                hub.notice.collectLatest { text ->
                    if (text == null) return@collectLatest
                    ui.notice = text
                    ui.show()
                    delay(LINK_NOTICE_MS)
                    if (ui.notice == text) ui.notice = null
                    hub.dismissNotice()
                }
            }
        }

        // Read once per session, before the file loads in practice; languages and mode only in the log
        lifecycleScope.launch {
            val read = AppServices.accountTracks(client, userId)
            account = read
            Log.i(TAG, "account audio=${read?.audioLanguages} default=${read?.playDefaultAudio} sub=${read?.subtitleLanguages} mode=${read?.subtitleMode}")
        }

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

        // Opened by a desktop's `load` from another screen: the hub kept it
        val linked = if (intent.getBooleanExtra(EXTRA_LINKED, false)) hub.takePendingLoad() else null
        if (linked != null) linkedLoad(linked) else play(itemId, startTicks, intent.getStringExtra(EXTRA_MEDIA_SOURCE_ID))
    }

    /** A `load` from the leader: the usual negotiation, its tracks, its position, paused (ADR 0124). */
    override fun linkedLoad(load: Body.Load) {
        linkLoad = load
        linkSpeed = SPEED_NORMAL
        readyJob?.cancel()
        readyJob = null
        play(load.itemId, load.positionMs * TICKS_PER_MS, load.mediaSourceId)
    }

    override fun linkedClose() = finish()

    override fun linkedEnded() = leaveLink()

    /** Recalage toward the leader, every 250 ms and at each `state`. */
    override fun evaluate() {
        val player = engine ?: return
        if (ui.status != null || readyJob != null || linkLoad?.itemId != current || evaluating?.isActive == true) return
        // mpv is read off the main thread, as the cache in awaitReady
        evaluating = lifecycleScope.launch {
            val position = withContext(Dispatchers.Default) { player.positionSeconds() } ?: return@launch
            if (engine === player && linkLoad?.itemId == current) correct(player, position)
        }
    }

    private fun correct(player: PlayerEngine, position: Double) {
        val target = hub.target(settings.syncOffsetMs.toLong()) ?: return
        val leader = hub.leader() ?: return
        val e = (position * 1000).roundToLong() - target
        hub.measured(e)
        val now = SystemClock.elapsedRealtime()
        val c = linkDecide(e, target, leader.paused || leader.buffering, ui.paused, linkSpeed != SPEED_NORMAL, now - lastSeekAt)
        if (c.pause) player.setPaused(true)
        if (c.resume) player.setPaused(false)
        c.speed?.let { linkSpeed = player.setSpeed(it) ?: it }
        c.seekTo?.let {
            lastSeekAt = now
            player.seekTo(it / 1000.0)
        }
    }

    /** The menu's link row: the desktop list out of a link, « Délier » in one. */
    private fun linkAction() {
        ui.closeMenus()
        if (ui.linkBusy) {
            hub.unlink()
            leaveLink()
        } else {
            hub.dismissNotice()
            ui.linkPicker = hub
        }
    }

    /** The TV plays on alone, at normal speed. */
    private fun leaveLink() {
        linkLoad = null
        readyJob?.cancel()
        readyJob = null
        evaluating?.cancel()
        if (linkSpeed != SPEED_NORMAL) engine?.setSpeed(SPEED_NORMAL)
        linkSpeed = SPEED_NORMAL
    }

    /** Once the linked title has read enough ahead, paused at its position: `ready`. */
    private fun awaitReady(load: Body.Load) {
        readyJob?.cancel()
        readyJob = lifecycleScope.launch {
            while (true) {
                val player = engine ?: return@launch
                // The link ended or moved on: nothing to answer
                if (!hub.loading(load.itemId)) {
                    readyJob = null
                    return@launch
                }
                val (ahead, idle) = withContext(Dispatchers.Default) { player.cacheAhead() }
                if (ahead >= READY_CACHE_SECONDS || idle) break
                delay(DECIDE_EVERY_MS)
            }
            val at = engine?.positionSeconds()?.let { (it * 1000).roundToLong() } ?: load.positionMs
            readyJob = null
            hub.ready(load.itemId, at)
        }
    }

    /** A user action on the TV, already applied here, sent to the leader in a link. */
    private fun relay(action: CmdAction, seconds: Double? = null) {
        if (!trailer) hub.command(action, seconds?.let { (it * 1000).roundToLong() })
    }

    /** [mediaSourceId]: the version chosen on the film page (ADR 0058); null lets the server pick. */
    private fun play(itemId: String, startTicks: Long, mediaSourceId: String? = null) {
        current = itemId
        next = null
        skips = null
        segments = emptyList()
        facts = null
        stream = null
        restore = null
        replacedEncoding = null
        dropThumbnails()
        settings = settingsStore?.load() ?: PlaybackSettings()
        ui.reset()
        // Minutes go on into the next episode; « Fin de l'épisode » was for the one before
        if (ui.sleep.holdsNext) ui.sleep = SleepTimer()
        ui.night = Night(settings.nightMode)
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
            ui.item = SleepItem.of(item.type)
            if (!trailer) lifecycleScope.launch { findSegments(item) }
            if (item.type == "Episode" && !trailer) {
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
        segments = found
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
            val linked = linkLoad?.takeIf { it.itemId == itemId }
            val first = plan(
                client.server,
                client.playbackInfo(userId, itemId, deviceProfile(bitrate), mediaSourceId, audioStreamIndex = linked?.audioStreamIndex),
                mediaSourceId,
            )
            if (current != itemId) return
            // In a link, the leader's audio comes first (ADR 0124)
            val chosen = if (linked != null) first else withRememberedAudio(itemId, first, bitrate)
            if (current != itemId) return
            Log.i(TAG, "play method=${chosen.method} source=${chosen.mediaSourceId} subtitles=${chosen.subtitles.size} maxBitrate=$bitrate")
            Log.i(TAG, "subtitle style=${settings.subtitles}")
            // Nothing fetched yet: the first seek asks for them, playback never waits (ADR 0069)
            thumbnails = SeekThumbnails(lifecycleScope, { downloadThumbnails(client, itemId) }) { Log.i(TAG, thumbnailsLog(it)) }
            val source = MediaSource.parse(chosen.url) ?: throw EmbyException(EmbyError.InvalidResponse("adresse de flux refusée"))
            val cap = settings.capFor(client.server.isLocal)
            adopt(chosen, cap, chosenInPlayer = false)
            ui.quality = Quality.of(cap, chosen.method, facts?.reasons.orEmpty())
            // Reported once the file is loaded; never if loading fails, never for a trailer
            if (!trailer) reporter.begin(
                client,
                userId,
                ActivePlayback(itemId, chosen.mediaSourceId, chosen.playSessionId, chosen.method, chosen.runTimeTicks, startTicks),
            )
            val running = engine
            if (running != null) {
                // Headers are fixed at mpv init (token out of logcat): the first file's apply
                replacedEnds++
                styleSubtitles(running)
                applyNightMode(running)
                play(running, source, startTicks / TICKS_PER_SECOND, paused = linked != null)
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
            applyNightMode(player)
            play(player, source, startTicks / TICKS_PER_SECOND, paused = linked != null)
        } catch (e: EmbyException) {
            ui.status = e.error.message
        }
    }

    /**
     * A transcode carries one audio, the server's default: when the remembered choice
     * names another of the source's audio streams, the server is asked again for it, as
     * on Windows (ADR 0035, 0084). Kept only if it stays a transcode of the same version;
     * a refusal keeps the first answer.
     */
    private suspend fun withRememberedAudio(itemId: String, first: PlaybackPlan, bitrate: Long): PlaybackPlan {
        val audio = rememberedAudioIndex(first, preferences?.load(TrackType.Audio)) ?: return first
        val second = try {
            plan(client.server, client.playbackInfo(userId, itemId, deviceProfile(bitrate), first.mediaSourceId, audioStreamIndex = audio), first.mediaSourceId)
        } catch (e: EmbyException) {
            Log.w(TAG, "remembered audio=$audio refused: ${e.error.message}")
            return first
        }
        val kept = keepsAudioRequest(first, second)
        Log.i(TAG, "remembered audio=$audio server=${first.serverTracks.audio} kept=$kept answered=${second.serverTracks.audio}")
        return if (kept) second else first
    }

    /** What the player keeps of a negotiated stream, at its first load or after a quality change (ADR 0081). */
    private fun adopt(chosen: PlaybackPlan, cap: Long?, chosenInPlayer: Boolean) {
        // The server's subtitle goes last, added selected; the others are added without (ADR 0055)
        subtitles = chosen.subtitles.sortedBy { it.selected }
        serverTracks = chosen.serverTracks
        transcode = chosen.method == PlayMethod.Transcode
        stream = chosen
        // Only the reasons are kept from the URL: it is never shown
        facts = PlaybackFacts(
            method = chosen.method,
            reasons = if (transcode) transcodeReasons(chosen.url) else emptyList(),
            cap = capFact(cap, client.server.isLocal, chosenInPlayer),
        )
    }

    /** ◀ ▶ on « Qualité »: the shown cap moves, nothing reloads (ADR 0081). */
    private fun pickQuality(change: QualityChange) {
        ui.quality = ui.quality.step(change)
    }

    /**
     * OK on « Qualité » (ADR 0081): the server is asked again at the shown cap, for the
     * version playing and from the current position, then the file reloads there. Only
     * one change at a time, and only once the file plays.
     */
    private fun applyQuality() {
        val itemId = current ?: return
        val playing = stream ?: return
        if (engine == null || ui.status != null || !ui.quality.pending) return
        val cap = ui.quality.chosen
        ui.quality = ui.quality.applying()
        val from = ui.position
        // The audio heard now: a transcode would otherwise carry the server's default (ADR 0084)
        val audio = engine?.let { playingAudioIndex(playing, it.tracks()) }
        lifecycleScope.launch { requalify(itemId, playing, cap, from, audio) }
    }

    private suspend fun requalify(itemId: String, playing: PlaybackPlan, cap: Long?, from: Double, audio: Int?) {
        val bitrate = cap ?: MAX_STREAMING_BITRATE
        val startTicks = (from * TICKS_PER_SECOND).roundToLong()
        val chosen = try {
            plan(
                client.server,
                client.playbackInfo(userId, itemId, deviceProfile(bitrate), playing.mediaSourceId, startTicks, audio),
                playing.mediaSourceId,
            )
        } catch (e: EmbyException) {
            if (current != itemId) return
            Log.w(TAG, "quality change refused maxBitrate=$bitrate: ${e.error.message}")
            ui.quality = ui.quality.failed()
            return
        }
        val player = engine
        // Another item started, or the player closed, during the request
        if (current != itemId || stream !== playing || player == null) return
        val source = MediaSource.parse(chosen.url)
        if (source == null) {
            Log.w(TAG, "quality change refused: stream address")
            ui.quality = ui.quality.failed()
            return
        }
        Log.i(TAG, "quality method=${chosen.method} source=${chosen.mediaSourceId} subtitles=${chosen.subtitles.size} maxBitrate=$bitrate " +
            "from=${playing.method} newSession=${chosen.playSessionId != playing.playSessionId} startInUrl=${hasStartParameter(chosen.url)} " +
            "audio=${audio ?: "-"} answered=${chosen.serverTracks.audio ?: "-"}")
        // The replaced transcode is ended once the new file plays, never before: mpv still reads it
        replacedEncoding = playing.playSessionId?.takeIf { playing.method == PlayMethod.Transcode && it != chosen.playSessionId }
        adopt(chosen, cap, chosenInPlayer = cap != settings.capFor(client.server.isLocal))
        // loadfile puts speed, delays and format back to their defaults: this playback keeps them
        restore = Adjustments(ui.speed, ui.delays, ui.fit)
        // Playback went on during the request: it resumes where it is now
        val at = ui.position
        val playback = ActivePlayback(itemId, chosen.mediaSourceId, chosen.playSessionId, chosen.method, chosen.runTimeTicks, (at * TICKS_PER_SECOND).roundToLong())
        report { switched(playback); emptyList() }
        ui.closeMenus()
        ui.quality = ui.quality.applied(chosen.method, facts?.reasons.orEmpty())
        ui.status = "Changement de qualité…"
        replacedEnds++
        play(player, source, at)
    }

    /** Loads a file; subtitles still being added to the replaced one are dropped (ADR 0085). */
    private fun play(player: PlayerEngine, source: MediaSource, at: Double, paused: Boolean = false) {
        subtitleJob?.cancel()
        player.load(source, at, paused)
    }

    /**
     * After a quality change, once the new file is loaded: speed, delays and format as
     * they were, then the server's transcode of the replaced stream ended (ADR 0081).
     */
    private fun restoreAdjustments(player: PlayerEngine) {
        val kept = restore ?: return
        restore = null
        val speed = if (kept.speed != NORMAL_SPEED) player.setSpeed(kept.speed) else NORMAL_SPEED
        DelayKind.entries.filter { kept.delays.of(it) != 0.0 }.forEach { player.setDelay(it, kept.delays.of(it)) }
        val fit = if (kept.fit != VideoFit.Original) player.setVideoFit(kept.fit) else VideoFit.Original
        Log.i(TAG, "quality restored speed=$speed delays=${kept.delays} fit=$fit")
        val ended = replacedEncoding ?: return
        replacedEncoding = null
        lifecycleScope.launch {
            try {
                client.stopEncoding(ended)
                Log.i(TAG, "replaced transcode ended")
            } catch (e: EmbyException) {
                // The server also ends a transcode nobody reads any more
                Log.w(TAG, "replaced transcode not ended: ${e.error.message}")
            }
        }
    }

    /** The subtitle look of Réglages for this item, above the controls if they show (ADR 0053). */
    private fun styleSubtitles(player: PlayerEngine) {
        player.setSubtitleStyle(settings.subtitles)
        player.setSubtitlePosition(subtitlePosition(settings.subtitles.position, ui.covered))
    }

    /** Night mode of Réglages, set before the file loads so its audio chain starts with it (ADR 0072). */
    private fun applyNightMode(player: PlayerEngine) {
        val held = player.setNightMode(settings.nightMode)
        Log.i(TAG, "night=${settings.nightMode} held=$held")
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
        report { fileLoaded(it) }
        runOnUiThread {
            ui.status = null
            ui.show()
        }
        // The tracks chosen in the menu are remembered: a quality change keeps them the same way
        applyTracks()
        engine?.let(::restoreAdjustments)
        addSubtitles(subtitles)
        runOnUiThread { linkLoad?.takeIf { it.itemId == current }?.let(::awaitReady) }
    }

    /**
     * `sub-add` waits for the whole file: the first time, the server may take minutes to
     * extract it. Added on their own thread, never on mpv's event thread, which would stop
     * positions and reports meanwhile; the tracks are then chosen again, an external one
     * may be the remembered choice (ADR 0085). A new file cancels the rest.
     */
    private fun addSubtitles(added: List<ExternalSubtitle>) {
        subtitleJob?.cancel()
        if (added.isEmpty()) return
        subtitleJob = lifecycleScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            // Named as the server describes them: the files themselves carry no title nor language (ADR 0084)
            for (sub in added) {
                val source = MediaSource.parse(sub.url) ?: continue
                ensureActive()
                engine?.addSubtitle(source, sub.selected, sub.title, sub.language)
            }
            ensureActive()
            Log.i(TAG, "subtitles added=${added.size} in ${System.currentTimeMillis() - started} ms")
            applyTracks()
        }
    }

    /**
     * After the external subtitles: one of them may be the remembered choice. The
     * remembered choice, else the server's pick, else the account's preferences, else
     * mpv's (ADR 0042, 0055, 0065). Audio first: `Smart` subtitles depend on its language.
     */
    private fun applyTracks() {
        val player = engine ?: return
        val store = preferences ?: return
        val tracks = player.tracks()
        val prefs = account
        var audioLang: String? = null
        val described = TrackType.entries.map { type ->
            val server = if (type == TrackType.Audio) serverTracks.audio else serverTracks.subtitle
            // A transcode carries the server's audio only: nothing for the account to pick
            val wanted = if (type == TrackType.Audio && transcode) null else accountPick(tracks, type, prefs, audioLang)
            // Languages set in Réglages on this device come before the server's account-based pick (ADR 0120)
            val local = localPick(tracks, type, prefs, settings.languages, audioLang, transcode)
            val decision = decide(tracks, type, store.load(type), server, transcode, wanted, local)
            if (type == TrackType.Audio) audioLang = playingAudioLanguage(tracks, decision)
            decision.selection?.let { player.selectTrack(type, it.id) }
            "${type.mpv}=${describeTrack(decision, player.property(type.property))}"
        }
        Log.i(TAG, "tracks ${described.joinToString(" ")}")
        linkLoad?.takeIf { it.itemId == current }?.let { linkedTracks(player, tracks, it) }
    }

    /** The leader's streams, by their Emby index: the file's own streams only; -1 turns subtitles off. */
    private fun linkedTracks(player: PlayerEngine, tracks: List<Track>, load: Body.Load) {
        if (!transcode) {
            load.audioStreamIndex?.let { index ->
                tracks.find { it.type == TrackType.Audio && !it.external && it.ffIndex == index }?.let { player.selectTrack(TrackType.Audio, it.id) }
            }
        }
        when (val index = load.subtitleStreamIndex) {
            null -> Unit
            -1 -> player.selectTrack(TrackType.Subtitle, null)
            else -> tracks.find { it.type == TrackType.Subtitle && !it.external && it.ffIndex == index }?.let { player.selectTrack(TrackType.Subtitle, it.id) }
        }
    }

    override fun onPosition(seconds: Double) {
        report { position(seconds, it) }
        runOnUiThread {
            ui.position = seconds
            ui.now = System.currentTimeMillis()
            // Paused by the timer: no skip button nor up-next card over the notice
            if (!checkTimer()) updateSkip()
        }
    }

    override fun onDuration(seconds: Double) {
        report { duration(seconds); emptyList() }
        runOnUiThread { ui.duration = seconds }
    }

    override fun onPauseChanged(paused: Boolean) {
        report { paused(paused, it) }
        runOnUiThread {
            ui.paused = paused
            if (!paused) resumed()
        }
    }

    /**
     * Playback goes on: the notice goes. Minutes that ran out while paused end there, so
     * playing again is not paused at once; « Fin de l'épisode » still waits for its stop.
     */
    private fun resumed() {
        ui.notice = null
        val now = System.currentTimeMillis()
        if (ui.sleep.choice is SleepChoice.Minutes && sleepDue(ui.sleep, now, ui.position, ui.duration, segments)) {
            Log.i(TAG, "sleep timer ran out while paused")
            ui.sleep = SleepTimer()
        }
    }

    override fun onSeek() {
        lastSeekAt = SystemClock.elapsedRealtime()
        report { seeked(); emptyList() }
    }

    override fun onBuffering(buffering: Boolean) {
        runOnUiThread { if (readyJob == null && !trailer) hub.buffering(buffering) }
    }

    override fun onEndReached() {
        report { ended(it) }
        runOnUiThread {
            // « Fin de l'épisode » not caught by a position: the player stays, without the next one
            if (ui.sleep.holdsNext && ui.upNext == null) {
                ranOut(pause = false)
                return@runOnUiThread
            }
            val following = next
            // A cut stream also reaches its end: no next episode then
            val natural = ui.duration <= 0 || ui.duration - ui.position <= NATURAL_END_MARGIN_SECONDS
            // Linked, the leader decides what comes next: the last frame stays (ADR 0124)
            if (ui.linkBusy) return@runOnUiThread
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
        report { ended(it) }
        runOnUiThread { finish() }
    }

    /** Pauses playback if the sleep timer has run out (ADR 0073); true when it did. */
    private fun checkTimer(): Boolean {
        // Until the new file loads, positions are still those of the replaced one
        if (ui.status != null || !ui.sleep.on) return false
        if (!sleepDue(ui.sleep, ui.now, ui.position, ui.duration, segments)) return false
        ranOut(pause = true)
        return true
    }

    /**
     * The timer ends: no up-next card, so no next episode, and a notice over the picture.
     * The pause goes through mpv as any other, so the tracker reports it the usual way;
     * at the end of the file ([pause] false), playback is already over.
     */
    private fun ranOut(pause: Boolean) {
        Log.i(TAG, "sleep timer ran out choice=${ui.sleep.choice} at=${ui.position}")
        ui.sleep = SleepTimer()
        ui.upNext = null
        ui.skip = null
        ui.closeMenus()
        if (pause && !ui.paused) engine?.togglePause()
        ui.notice = SLEEP_NOTICE
        ui.show()
    }

    /** The « Minuterie » row: a new value starts from now; the same one, held at an end, keeps its time. */
    private fun changeTimer(change: SleepChange) {
        val choice = changeSleep(ui.sleep.choice, change, ui.item)
        if (choice == ui.sleep.choice) return
        val now = System.currentTimeMillis()
        ui.sleep = sleepTimer(choice, now, ui.position)
        ui.now = now
        Log.i(TAG, "sleep timer=$choice")
    }

    /** The skip button, or the up-next card from the start of the credits. */
    private fun updateSkip() {
        // « Fin de l'épisode » set: the credits get their button, never the next episode (ADR 0073)
        // Nor while linked: the leader moves to the next episode (ADR 0124)
        val hasNext = next != null && !ui.sleep.holdsNext && !ui.linkBusy
        // Until the new file loads, positions are still those of the replaced one
        val prompt = if (ui.status == null) skips?.at(ui.position, ui.duration, hasNext) else null
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
        val since = trackSkipShown(ui.skipSince, ui.skip, System.currentTimeMillis())
        // Entered by a seek (◀ back into the intro): no focus, OK keeps playing or pausing (ADR 0108)
        if (since !== ui.skipSince && since.button != null) ui.skipArmed = SkipArm.appeared(ui.controls)
        ui.skipSince = since
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
        relay(CmdAction.Seek, target)
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
        // Run out during the card's countdown, when no position comes: the next episode does not start
        if (sleepDue(ui.sleep, System.currentTimeMillis(), ui.position, ui.duration, segments)) {
            ranOut(pause = ui.upNext?.atCredits == true)
            return
        }
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
        // OK presses the skip button while it shows; play / pause keys still pause.
        // Once it hid (ADR 0089), OK only brings it back with the controls
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        // After a ◀ ▶ seek the button is disarmed: OK plays or pauses (ADR 0108)
        val okDoes = if (ok && ui.skip != null) SkipArm.onOk(ui.skipVisible, ui.skipArmed) else SkipOk.PlayPause
        if (okDoes != SkipOk.PlayPause) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (okDoes == SkipOk.Skip) {
                    skip()
                } else {
                    ui.skipArmed = true
                    ui.show()
                }
            }
            return true
        }
        val action = action(event) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) action(player)
        if (event.action == KeyEvent.ACTION_UP && heldMoved) {
            heldMoved = false
            relay(CmdAction.Seek, ui.position)
        }
        return true
    }

    private fun action(event: KeyEvent): ((PlayerEngine) -> Unit)? = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { p ->
            // ui.paused is still the state before the toggle
            relay(if (ui.paused) CmdAction.Play else CmdAction.Pause)
            p.togglePause()
            ui.show()
        }
        // Only sent: the leader answers with the `load` of the episode (ADR 0124)
        KeyEvent.KEYCODE_MEDIA_NEXT -> { _ -> relay(CmdAction.Next) }
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { _ -> relay(CmdAction.Previous) }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p -> heldSeek(p, -1.0, event.repeatCount) }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p -> heldSeek(p, 1.0, event.repeatCount) }
        KeyEvent.KEYCODE_DPAD_UP -> { _ ->
            ui.chapterMenu = ui.chapters.isNotEmpty()
            ui.show()
        }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { p ->
            ui.tracks = p.tracks()
            // Audio passed through to the amplifier: night mode says it cannot apply (ADR 0072)
            if (p.audioPassthrough()) ui.night = ui.night.copy(state = NightState.Passthrough)
            ui.show()
        }
        // Remotes that have it; the Shield's has not, and goes through the track menu
        KeyEvent.KEYCODE_INFO -> { _ -> openInfo() }
        else -> null
    }

    /** ◀ ▶ held (ADR 0090): the step grows with the repeats, and only some repeats seek. */
    private fun heldSeek(player: PlayerEngine, direction: Double, repeatCount: Int) {
        if (!seeksOnRepeat(repeatCount)) return
        // One press is sent at once; a hold sends where it ends, on release
        if (repeatCount > 0) heldMoved = true
        seek(player, direction * seekStep(repeatCount), send = repeatCount == 0)
    }

    private fun seek(player: PlayerEngine, seconds: Double, send: Boolean = true) {
        player.seekBy(seconds)
        // The OK that follows a seek plays or pauses, never skips (ADR 0108)
        ui.skipArmed = false
        // Shown at once; mpv's next position replaces it
        val end = if (ui.duration > 0) ui.duration else Double.MAX_VALUE
        ui.position = (ui.position + seconds).coerceIn(0.0, end)
        if (send) relay(CmdAction.Seek, ui.position)
        ui.seeking(ui.position)
        preview(ui.position)
        ui.show()
    }

    /**
     * The thumbnail at [target] above the bar (ADR 0069): the BIF image nearest before it,
     * decoded off the main thread, unless it is the one already shown. Without thumbnails
     * for this file, nothing shows.
     */
    private fun preview(target: Double) {
        val thumbs = thumbnails ?: return
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val frame = thumbs.frameAt(target) ?: return@launch
            if (ui.seekFrame?.index == frame.index) return@launch
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(frame.bytes, frame.offset, frame.length)
            } ?: return@launch
            // Another file may have started during the decoding
            if (thumbnails === thumbs) ui.seekFrame = SeekFrame(frame.index, bitmap.asImageBitmap())
        }
    }

    /** Before another file and at teardown: the thumbnails of this one are dropped from memory. */
    private fun dropThumbnails() {
        previewJob?.cancel()
        previewJob = null
        thumbnails?.close()
        thumbnails = null
    }

    /** A row of the chapter menu: playback goes to its start and the menu closes. */
    private fun goToChapter(start: Double) {
        val player = engine ?: return
        Log.i(TAG, "chapter at $start")
        player.seekTo(start)
        relay(CmdAction.Seek, start)
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

    /** A delay row of the track menu: what mpv holds afterwards is shown (ADR 0062). */
    private fun shiftDelay(kind: DelayKind, change: DelayChange) {
        val player = engine ?: return
        val applied = player.setDelay(kind, changeDelay(ui.delays.of(kind), change))
        Log.i(TAG, "${kind.property}=$applied")
        // Unreadable: the row keeps the last value mpv gave
        if (applied != null) ui.delays = ui.delays.with(kind, applied)
    }

    /** The speed row of the track menu: what mpv holds afterwards is shown (ADR 0072). */
    private fun shiftSpeed(change: SpeedChange) {
        val player = engine ?: return
        val applied = player.setSpeed(changeSpeed(ui.speed, change))
        Log.i(TAG, "speed=$applied pitch-correction=${player.property("audio-pitch-correction")}")
        if (applied != null) ui.speed = applied
    }

    /** The format row of the track menu: what mpv holds afterwards is shown (ADR 0078). */
    private fun shiftFit(change: FitChange) {
        val player = engine ?: return
        val applied = player.setVideoFit(changeFit(ui.fit, change))
        Log.i(TAG, "fit=$applied panscan=${player.property(PANSCAN_PROPERTY)} keepaspect=${player.property(KEEPASPECT_PROPERTY)}")
        // Unreadable or partial: the row keeps the last fit mpv gave
        if (applied != null) ui.fit = applied
    }

    /**
     * The night mode row: saved in Réglages at once, then applied live when mpv takes it.
     * In passthrough, saved only: no filter can touch audio that is not decoded.
     */
    private fun toggleNight() {
        val player = engine ?: return
        val on = !ui.night.on
        settingsStore?.let { store -> store.save(store.load().copy(nightMode = on)) }
        settings = settings.copy(nightMode = on)
        val state = when {
            player.audioPassthrough() -> NightState.Passthrough
            player.setNightMode(on) -> NightState.Applied
            else -> NightState.NextPlayback
        }
        Log.i(TAG, "night=$on state=$state af=${player.property("af")}")
        ui.night = Night(on, state)
    }

    override fun onStop() {
        super.onStop()
        // Home button or another app: playback ends, it does not go on unseen
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        // Back, Home or the end: the desktop stops too (`bye stopped`, ADR 0124)
        if (!trailer) {
            if (ui.linkBusy) hub.stopped()
            hub.detach(this)
        }
        // Before release: the tracker keeps the last position mpv reported
        report { closed(it) }
        dropThumbnails()
        engine?.release()
        engine = null
        super.onDestroy()
    }

    /** Per-file settings of this playback that a reload would lose (ADR 0062, 0072, 0078). */
    private data class Adjustments(val speed: Double, val delays: Delays, val fit: VideoFit)

    companion object {
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_START_TICKS = "startTicks"
        const val EXTRA_MEDIA_SOURCE_ID = "mediaSourceId"

        /** True for a local trailer (ADR 0083): played without any report. */
        const val EXTRA_TRAILER = "trailer"

        /** Opened by a desktop's `load`: the hub holds it (ADR 0124). */
        const val EXTRA_LINKED = "linked"
    }
}
