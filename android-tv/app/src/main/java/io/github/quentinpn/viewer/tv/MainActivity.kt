package io.github.quentinpn.viewer.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.session.LostConnection
import io.github.quentinpn.viewer.tv.session.SearchHistoryStore
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.session.SessionCheck
import io.github.quentinpn.viewer.tv.session.autoRetry
import io.github.quentinpn.viewer.tv.session.checkSession
import io.github.quentinpn.viewer.tv.session.failed
import io.github.quentinpn.viewer.tv.session.hostLabel
import io.github.quentinpn.viewer.tv.session.isConnectionLoss
import io.github.quentinpn.viewer.tv.session.retryNow
import io.github.quentinpn.viewer.tv.ui.ConnectionLost
import io.github.quentinpn.viewer.tv.settings.PlaybackSettingsStore
import io.github.quentinpn.viewer.tv.ui.CastMember
import io.github.quentinpn.viewer.tv.ui.DetailPage
import io.github.quentinpn.viewer.tv.ui.EpisodeScreen
import io.github.quentinpn.viewer.tv.ui.HomeMemory
import io.github.quentinpn.viewer.tv.ui.HomeRowStore
import io.github.quentinpn.viewer.tv.ui.HomeScreen
import io.github.quentinpn.viewer.tv.ui.HomeState
import io.github.quentinpn.viewer.tv.ui.LibraryMemory
import io.github.quentinpn.viewer.tv.ui.LibraryPreferenceStore
import io.github.quentinpn.viewer.tv.ui.LibraryScreen
import io.github.quentinpn.viewer.tv.ui.MovieScreen
import io.github.quentinpn.viewer.tv.ui.PersonScreen
import io.github.quentinpn.viewer.tv.ui.PlaylistScreen
import io.github.quentinpn.viewer.tv.ui.PreConnectionScreen
import io.github.quentinpn.viewer.tv.ui.SearchMemory
import io.github.quentinpn.viewer.tv.ui.SearchScreen
import io.github.quentinpn.viewer.tv.ui.SeriesScreen
import io.github.quentinpn.viewer.tv.ui.SeriesTarget
import io.github.quentinpn.viewer.tv.ui.SettingsScreen
import io.github.quentinpn.viewer.tv.ui.TrailerHandlers
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import io.github.quentinpn.viewer.tv.ui.VersionStore
import io.github.quentinpn.viewer.tv.ui.ancestorIdsOrNull
import io.github.quentinpn.viewer.tv.ui.backFromEpisode
import io.github.quentinpn.viewer.tv.ui.genreOptions
import io.github.quentinpn.viewer.tv.ui.openFromCollection
import io.github.quentinpn.viewer.tv.ui.openGenre
import io.github.quentinpn.viewer.tv.ui.openOver
import io.github.quentinpn.viewer.tv.ui.openStudio
import io.github.quentinpn.viewer.tv.ui.pageKey
import io.github.quentinpn.viewer.tv.ui.playlistEntryPage
import io.github.quentinpn.viewer.tv.ui.replaceTop
import io.github.quentinpn.viewer.tv.ui.similarPage
import io.github.quentinpn.viewer.tv.ui.studioOptions
import io.github.quentinpn.viewer.tv.ui.titleLibrary
import io.github.quentinpn.viewer.tv.update.HomeUpdate
import io.github.quentinpn.viewer.tv.update.Updater
import kotlinx.coroutines.launch

/** Launcher: the server shelf when no session is open (ADR 0104), else the home shelves, a library grid or the search, the settings, the series and film pages. */
class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Back from « sources inconnues »: the install the user asked for goes on if allowed
        if (Updater.awaitingPermission) {
            Updater.awaitingPermission = false
            if (Updater.canInstall(this)) lifecycleScope.launch { Updater.install(this@MainActivity) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AppServices.store(this)
        val device = AppServices.device(this)
        val reporter = AppServices.reporter(this)
        // Updates (ADR 0112): checked at start, then every 6 h while the app is in front
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    Updater.checkIfDue(this@MainActivity, AppServices.http)
                    kotlinx.coroutines.delay(Updater.CHECK_INTERVAL_MS)
                }
            }
        }
        setContent {
            ViewerTheme {
                var session by remember { mutableStateOf(store.load()) }
                // A session refused while in use: the shelf opens on its « Session expirée » card (ADR 0104)
                var reauth by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                val current = session
                if (current == null) {
                    PreConnectionScreen(
                        store = store,
                        device = device,
                        http = AppServices.http,
                        reauthKey = reauth,
                        onSignedIn = { signedIn: Session ->
                            reauth = null
                            session = signedIn
                        },
                        onRevoke = { entry, token ->
                            // Best effort, never waited for: the tile is already gone
                            scope.launch {
                                try {
                                    AppServices.client(this@MainActivity, Session(entry.server, entry.userId, entry.userName, token)).logout()
                                } catch (_: EmbyException) {
                                }
                            }
                        },
                    )
                } else {
                    val client = remember(current) { AppServices.client(this, current) }
                    val images = remember(client) { AppServices.images(this, client) }
                    // Detail pages, the shown one last: a similar title, an episode, a person and their titles stack theirs (ADR 0063, 0068)
                    var pages by remember(current) { mutableStateOf<List<DetailPage>>(emptyList()) }
                    val openSeries = { target: SeriesTarget -> pages = listOf(DetailPage.Series(target)) }
                    val openMovie = { id: String -> pages = listOf(DetailPage.Movie(id)) }
                    val openSimilar: (BaseItemDto) -> Unit = { item -> similarPage(item)?.let { pages = openOver(pages, it) } }
                    // The page below remembers the card, focused again on Back
                    val openPerson: (CastMember) -> Unit = { member -> pages = openOver(pages, DetailPage.Person(member), member.id) }
                    var library by remember(current) { mutableStateOf<LibraryMemory?>(null) }
                    var search by remember(current) { mutableStateOf<SearchMemory?>(null) }
                    var settings by remember(current) { mutableStateOf(false) }
                    val libraryPrefs = remember(current) { LibraryPreferenceStore(this, current.userId) }
                    val settingsStore = remember(current) { PlaybackSettingsStore(this, current.userId) }
                    val homeRows = remember(current) { HomeRowStore(this, current.userId) }
                    val searchHistory = remember(current) { SearchHistoryStore(this, current.server, current.userId) }
                    val home = remember(current) { HomeMemory() }
                    // Sign-in or restored session: stop reports lost to the network go out now (ADR 0066)
                    LaunchedEffect(client) { reporter.signedIn(client, current.userId) }
                    // The tile of a session opened at start (or migrated) learns the server's name (ADR 0104)
                    LaunchedEffect(client) {
                        try {
                            val info = client.publicInfo()
                            store.describe(current.key, info.id, info.serverName)
                        } catch (_: EmbyException) {
                        }
                    }
                    val refresh by reporter.stopped.collectAsState()
                    val versions = remember(current) { VersionStore(this, current.userId) }
                    // A version is only chosen on the film and episode pages (ADR 0058, 0060); elsewhere the server picks
                    val playSource = { itemId: String, startTicks: Long, mediaSourceId: String? ->
                        startActivity(
                            Intent(this, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId)
                                .putExtra(PlayerActivity.EXTRA_START_TICKS, startTicks)
                                .putExtra(PlayerActivity.EXTRA_MEDIA_SOURCE_ID, mediaSourceId),
                        )
                    }
                    val play = { itemId: String, startTicks: Long -> playSource(itemId, startTicks, null) }
                    // A local trailer plays without reports; a checked web address opens outside the app, as is (ADR 0083)
                    val trailers = TrailerHandlers(
                        play = { itemId ->
                            startActivity(
                                Intent(this, PlayerActivity::class.java)
                                    .putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId)
                                    .putExtra(PlayerActivity.EXTRA_TRAILER, true),
                            )
                        },
                        open = { url ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                true
                            } catch (_: ActivityNotFoundException) {
                                false
                            }
                        },
                    )
                    val expired = {
                        reporter.signedOut()
                        // The tile stays without its token; « Session expirée » asks the password
                        store.signOut()
                        reauth = current.key
                        session = null
                    }
                    // The token is kept: OK on the tile opens this session again (ADR 0104)
                    val switchServer = {
                        reporter.signedOut()
                        store.deactivate()
                        session = null
                    }
                    // « Connexion perdue » (ADR 0119): a network error of the home is checked once,
                    // quietly; if the server is really away the screen shows, with automatic attempts.
                    // Every state above stays remembered, so the app comes back where it was.
                    var suspect by remember(current) { mutableStateOf(false) }
                    var lost by remember(current) { mutableStateOf<LostConnection?>(null) }
                    val reportError: (EmbyError) -> Unit = { error -> if (lost == null && isConnectionLoss(error)) suspect = true }
                    val recovered = {
                        lost = null
                        if (home.state is HomeState.Failed) {
                            home.state = HomeState.Loading
                            home.attempt++
                        } else {
                            home.userDataChanged()
                        }
                    }
                    LaunchedEffect(suspect) {
                        if (!suspect) return@LaunchedEffect
                        when (val check = checkSession(client, current.userId)) {
                            SessionCheck.Ok -> Unit
                            SessionCheck.Refused -> expired()
                            is SessionCheck.Failed -> lost = null.failed(check.error, SystemClock.elapsedRealtime(), Math.random())
                        }
                        suspect = false
                    }
                    LaunchedEffect(lost) {
                        val state = lost ?: return@LaunchedEffect
                        if (state.checking) {
                            when (val check = checkSession(client, current.userId)) {
                                SessionCheck.Ok -> recovered()
                                SessionCheck.Refused -> {
                                    lost = null
                                    expired()
                                }
                                is SessionCheck.Failed -> lost = state.failed(check.error, SystemClock.elapsedRealtime(), Math.random())
                            }
                        } else {
                            val at = state.retryAt ?: return@LaunchedEffect
                            kotlinx.coroutines.delay((at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                            lost = state.autoRetry()
                        }
                    }
                    val lostNow = lost
                    if (lostNow != null) {
                        ConnectionLost(
                            lostNow,
                            serverName = store.server(current.key)?.displayName ?: hostLabel(current.server),
                            serverLabel = "${hostLabel(current.server)} · ${current.userName}",
                            onRetry = { lost = lostNow.retryNow() },
                            onChangeServer = switchServer,
                        )
                        return@ViewerTheme
                    }
                    // A genre chip opens the library holding the title on that genre (ADR 0079, 0093)
                    val libraries = (home.state as? HomeState.Ready)?.libraries.orEmpty()
                    val genreOpener: (String, List<String>?) -> ((String) -> Unit)? = { itemType, ancestors ->
                        titleLibrary(libraries, itemType, ancestors)?.let { library ->
                            { genre -> genreOptions(libraryPrefs.load(library), genre)?.let { pages = openGenre(pages, DetailPage.Grid(LibraryMemory(library, it)), genre) } }
                        }
                    }
                    // A studio chip opens that same library on the studio, by name (ADR 0087)
                    val studioOpener: (String, List<String>?) -> ((String) -> Unit)? = { itemType, ancestors ->
                        titleLibrary(libraries, itemType, ancestors)?.let { library ->
                            { studio -> studioOptions(libraryPrefs.load(library), studio)?.let { pages = openStudio(pages, DetailPage.Grid(LibraryMemory(library, it)), studio) } }
                        }
                    }
                    val page = pages.lastOrNull()
                    if (page != null) {
                        // Back shows the page below: the film or series a similar title was opened from
                        BackHandler { pages = pages.dropLast(1) }
                        // Fresh state per page: a similar series replaces this one at the same place
                        key(pages.size, pageKey(page)) {
                            // Read with the page, cancelled with it; until then the chips open sectionLibrary's choice (ADR 0093)
                            val titleId = when (page) {
                                is DetailPage.Movie -> page.id
                                is DetailPage.Series -> page.target.seriesId
                                else -> null
                            }
                            val ancestors by produceState<List<String>?>(null, titleId) {
                                value = titleId?.let { ancestorIdsOrNull(client, current.userId, it) }
                            }
                            when (page) {
                                // Over the series page: Back shows it again, on the episode's season and card (ADR 0060)
                                is DetailPage.Episode -> EpisodeScreen(
                                    client,
                                    current.userId,
                                    page.target,
                                    images,
                                    versions,
                                    onPlay = playSource,
                                    onNavigate = { pages = replaceTop(pages, DetailPage.Episode(it)) },
                                    onBack = { pages = backFromEpisode(pages, it) },
                                    onOpenPerson = openPerson,
                                    onSessionExpired = expired,
                                    refresh = refresh,
                                    focusPerson = page.person,
                                    onUserDataChanged = home::userDataChanged,
                                )
                                is DetailPage.Grid -> LibraryScreen(
                                    client,
                                    current.userId,
                                    images,
                                    page.memory,
                                    // Not saved: the grid of the home bar keeps its own choices (ADR 0079, 0087)
                                    onOptionsChanged = {},
                                    onOpenSeries = { pages = openOver(pages, DetailPage.Series(SeriesTarget(it))) },
                                    onOpenMovie = { pages = openOver(pages, DetailPage.Movie(it)) },
                                    onSessionExpired = expired,
                                    onPlay = play,
                                    onUserDataChanged = home::userDataChanged,
                                )
                                is DetailPage.Person -> PersonScreen(
                                    client,
                                    current.userId,
                                    page,
                                    images,
                                    // The person page remembers the title, focused again on Back
                                    onOpen = { item -> similarPage(item)?.let { pages = openOver(pages, it, item.id) } },
                                    onSessionExpired = expired,
                                )
                                is DetailPage.Playlist -> PlaylistScreen(
                                    client,
                                    current.userId,
                                    page,
                                    images,
                                    // The playlist remembers the entry, focused again on Back (ADR 0088)
                                    onOpen = { item -> playlistEntryPage(item)?.let { pages = openOver(pages, it, item.id) } },
                                    onSessionExpired = expired,
                                )
                                is DetailPage.Series -> SeriesScreen(
                                    client,
                                    current.userId,
                                    page.target,
                                    images,
                                    onPlay = play,
                                    onOpenEpisode = { pages = openOver(pages, DetailPage.Episode(it)) },
                                    onOpenSimilar = openSimilar,
                                    onOpenPerson = openPerson,
                                    onSessionExpired = expired,
                                    refresh = refresh,
                                    focusPerson = page.person,
                                    onOpenGenre = genreOpener("Series", ancestors),
                                    focusGenre = page.genre,
                                    trailers = trailers,
                                    onOpenStudio = studioOpener("Series", ancestors),
                                    focusStudio = page.studio,
                                    onUserDataChanged = home::userDataChanged,
                                )
                                is DetailPage.Movie -> MovieScreen(
                                    client,
                                    current.userId,
                                    page.id,
                                    images,
                                    versions,
                                    onPlay = playSource,
                                    onOpenSimilar = openSimilar,
                                    onOpenPerson = openPerson,
                                    // The film remembers the poster, focused again on Back (ADR 0071)
                                    onOpenCollectionItem = { item -> similarPage(item)?.let { pages = openFromCollection(pages, it, item.id) } },
                                    onSessionExpired = expired,
                                    refresh = refresh,
                                    focusPerson = page.person,
                                    focusCollection = page.collection,
                                    onOpenGenre = genreOpener("Movie", ancestors),
                                    focusGenre = page.genre,
                                    trailers = trailers,
                                    onOpenStudio = studioOpener("Movie", ancestors),
                                    focusStudio = page.studio,
                                    onUserDataChanged = home::userDataChanged,
                                )
                            }
                        }
                        return@ViewerTheme
                    }
                    // Under the film and series pages: Back from them returns to the grid
                    val openLibrary = library
                    if (openLibrary != null) {
                        BackHandler { library = null }
                        LibraryScreen(
                            client,
                            current.userId,
                            images,
                            openLibrary,
                            onOptionsChanged = { libraryPrefs.save(openLibrary.library, it) },
                            onOpenSeries = { openSeries(SeriesTarget(it)) },
                            onOpenMovie = openMovie,
                            onSessionExpired = expired,
                            onPlay = play,
                            onUserDataChanged = home::userDataChanged,
                        )
                        return@ViewerTheme
                    }
                    // Also under the film and series pages: Back from them returns to the results
                    val openSearch = search
                    if (openSearch != null) {
                        BackHandler { search = null }
                        SearchScreen(
                            client,
                            current.userId,
                            images,
                            openSearch,
                            searchHistory,
                            onOpenSeries = openSeries,
                            onOpenMovie = openMovie,
                            onSessionExpired = expired,
                        )
                        return@ViewerTheme
                    }
                    if (settings) {
                        BackHandler { settings = false }
                        SettingsScreen(
                            settingsStore,
                            localAddress = client.server.isLocal,
                            client = client,
                            images = images,
                            userId = current.userId,
                            rowStore = homeRows,
                        )
                        return@ViewerTheme
                    }
                    // Over the home only, never over a page or the player
                    HomeUpdate()
                    HomeScreen(
                        client = client,
                        userId = current.userId,
                        userName = current.userName,
                        images = images,
                        onPlay = play,
                        onOpenSeries = { openSeries(SeriesTarget(it)) },
                        onOpenMovie = openMovie,
                        onOpenPlaylist = { pages = listOf(DetailPage.Playlist(it.id, it.name)) },
                        onOpenLibrary = { library = LibraryMemory(it, libraryPrefs.load(it)) },
                        onOpenSearch = { search = SearchMemory() },
                        onOpenSettings = { settings = true },
                        onSessionExpired = expired,
                        rowStore = homeRows,
                        memory = home,
                        refresh = refresh,
                        onSignOut = {
                            // Revoke the token on the server; signing out locally works offline too
                            scope.launch {
                                try {
                                    client.logout()
                                } catch (_: EmbyException) {
                                }
                            }
                            reporter.signedOut()
                            // The tile stays on the shelf, without its token (ADR 0104)
                            store.signOut()
                            session = null
                        },
                        onSwitchServer = switchServer,
                        onConnectionError = reportError,
                    )
                }
            }
        }
    }
}
