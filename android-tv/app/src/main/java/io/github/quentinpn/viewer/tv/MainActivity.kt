package io.github.quentinpn.viewer.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.settings.PlaybackSettingsStore
import io.github.quentinpn.viewer.tv.ui.EpisodeScreen
import io.github.quentinpn.viewer.tv.ui.HomeMemory
import io.github.quentinpn.viewer.tv.ui.HomeScreen
import io.github.quentinpn.viewer.tv.ui.LibraryMemory
import io.github.quentinpn.viewer.tv.ui.LibraryPreferenceStore
import io.github.quentinpn.viewer.tv.ui.LibraryScreen
import io.github.quentinpn.viewer.tv.ui.MovieScreen
import io.github.quentinpn.viewer.tv.ui.SearchMemory
import io.github.quentinpn.viewer.tv.ui.SearchScreen
import io.github.quentinpn.viewer.tv.ui.SeriesScreen
import io.github.quentinpn.viewer.tv.ui.SeriesTarget
import io.github.quentinpn.viewer.tv.ui.SettingsScreen
import io.github.quentinpn.viewer.tv.ui.SignInScreen
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import io.github.quentinpn.viewer.tv.ui.VersionStore
import kotlinx.coroutines.launch

/** Launcher: sign-in when no session is stored, else the home shelves, a library grid or the search, the settings, the series and film pages. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AppServices.store(this)
        val device = AppServices.device(this)
        setContent {
            ViewerTheme {
                var session by remember { mutableStateOf(store.load()) }
                var notice by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                val current = session
                if (current == null) {
                    SignInScreen(store.lastServer, notice, device, AppServices.http) { signedIn: Session ->
                        store.save(signedIn)
                        notice = null
                        session = signedIn
                    }
                } else {
                    val client = remember(current) { AppServices.client(this, current) }
                    val images = remember(client) { AppServices.images(this, client) }
                    var series by remember(current) { mutableStateOf<SeriesTarget?>(null) }
                    var episode by remember(current) { mutableStateOf<SeriesTarget?>(null) }
                    var movie by remember(current) { mutableStateOf<String?>(null) }
                    var library by remember(current) { mutableStateOf<LibraryMemory?>(null) }
                    var search by remember(current) { mutableStateOf<SearchMemory?>(null) }
                    var settings by remember(current) { mutableStateOf(false) }
                    val libraryPrefs = remember(current) { LibraryPreferenceStore(this, current.userId) }
                    val settingsStore = remember(current) { PlaybackSettingsStore(this, current.userId) }
                    val home = remember(current) { HomeMemory() }
                    val refresh by AppServices.reporter.stopped.collectAsState()
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
                    val expired = {
                        store.clear()
                        notice = "Session expirée. Reconnectez-vous."
                        session = null
                    }
                    // Over the series page: Back shows it again, on the episode's season and card (ADR 0060)
                    val openEpisode = episode
                    if (openEpisode != null) {
                        EpisodeScreen(
                            client,
                            current.userId,
                            openEpisode,
                            images,
                            versions,
                            onPlay = playSource,
                            onNavigate = { episode = it },
                            onBack = {
                                series = it
                                episode = null
                            },
                            onSessionExpired = expired,
                            refresh = refresh,
                        )
                        return@ViewerTheme
                    }
                    val openSeries = series
                    if (openSeries != null) {
                        BackHandler { series = null }
                        SeriesScreen(
                            client,
                            current.userId,
                            openSeries,
                            images,
                            onPlay = play,
                            onOpenEpisode = { episode = it },
                            onSessionExpired = expired,
                            refresh = refresh,
                        )
                        return@ViewerTheme
                    }
                    val openMovie = movie
                    if (openMovie != null) {
                        BackHandler { movie = null }
                        MovieScreen(client, current.userId, openMovie, images, versions, onPlay = playSource, onSessionExpired = expired, refresh = refresh)
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
                            onOpenSeries = { series = SeriesTarget(it) },
                            onOpenMovie = { movie = it },
                            onSessionExpired = expired,
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
                            onOpenSeries = { series = it },
                            onOpenMovie = { movie = it },
                            onSessionExpired = expired,
                        )
                        return@ViewerTheme
                    }
                    if (settings) {
                        BackHandler { settings = false }
                        SettingsScreen(
                            settingsStore,
                            localAddress = client.server.isLocal,
                            version = device.version,
                        )
                        return@ViewerTheme
                    }
                    HomeScreen(
                        client = client,
                        userId = current.userId,
                        userName = current.userName,
                        images = images,
                        onPlay = play,
                        onOpenSeries = { series = SeriesTarget(it) },
                        onOpenMovie = { movie = it },
                        onOpenLibrary = { library = LibraryMemory(it, libraryPrefs.load(it)) },
                        onOpenSearch = { search = SearchMemory() },
                        onOpenSettings = { settings = true },
                        onSessionExpired = expired,
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
                            store.clear()
                            session = null
                        },
                    )
                }
            }
        }
    }
}
