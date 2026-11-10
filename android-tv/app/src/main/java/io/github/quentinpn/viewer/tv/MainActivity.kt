package io.github.quentinpn.viewer.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.quentinpn.viewer.tv.emby.BaseItemDto
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.settings.PlaybackSettingsStore
import io.github.quentinpn.viewer.tv.ui.CastMember
import io.github.quentinpn.viewer.tv.ui.DetailPage
import io.github.quentinpn.viewer.tv.ui.EpisodeScreen
import io.github.quentinpn.viewer.tv.ui.HomeMemory
import io.github.quentinpn.viewer.tv.ui.HomeScreen
import io.github.quentinpn.viewer.tv.ui.LibraryMemory
import io.github.quentinpn.viewer.tv.ui.LibraryPreferenceStore
import io.github.quentinpn.viewer.tv.ui.LibraryScreen
import io.github.quentinpn.viewer.tv.ui.MovieScreen
import io.github.quentinpn.viewer.tv.ui.PersonScreen
import io.github.quentinpn.viewer.tv.ui.SearchMemory
import io.github.quentinpn.viewer.tv.ui.SearchScreen
import io.github.quentinpn.viewer.tv.ui.SeriesScreen
import io.github.quentinpn.viewer.tv.ui.SeriesTarget
import io.github.quentinpn.viewer.tv.ui.SettingsScreen
import io.github.quentinpn.viewer.tv.ui.SignInScreen
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import io.github.quentinpn.viewer.tv.ui.VersionStore
import io.github.quentinpn.viewer.tv.ui.backFromEpisode
import io.github.quentinpn.viewer.tv.ui.openFromCollection
import io.github.quentinpn.viewer.tv.ui.openOver
import io.github.quentinpn.viewer.tv.ui.pageKey
import io.github.quentinpn.viewer.tv.ui.replaceTop
import io.github.quentinpn.viewer.tv.ui.similarPage
import kotlinx.coroutines.launch

/** Launcher: sign-in when no session is stored, else the home shelves, a library grid or the search, the settings, the series and film pages. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AppServices.store(this)
        val device = AppServices.device(this)
        val reporter = AppServices.reporter(this)
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
                    val home = remember(current) { HomeMemory() }
                    // Sign-in or restored session: stop reports lost to the network go out now (ADR 0066)
                    LaunchedEffect(client) { reporter.signedIn(client, current.userId) }
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
                    val expired = {
                        reporter.signedOut()
                        store.clear()
                        notice = "Session expirée. Reconnectez-vous."
                        session = null
                    }
                    val page = pages.lastOrNull()
                    if (page != null) {
                        // Back shows the page below: the film or series a similar title was opened from
                        BackHandler { pages = pages.dropLast(1) }
                        // Fresh state per page: a similar series replaces this one at the same place
                        key(pages.size, pageKey(page)) {
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
                        )
                        return@ViewerTheme
                    }
                    HomeScreen(
                        client = client,
                        userId = current.userId,
                        userName = current.userName,
                        images = images,
                        onPlay = play,
                        onOpenSeries = { openSeries(SeriesTarget(it)) },
                        onOpenMovie = openMovie,
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
                            reporter.signedOut()
                            store.clear()
                            session = null
                        },
                    )
                }
            }
        }
    }
}
