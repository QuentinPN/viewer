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
import io.github.quentinpn.viewer.tv.ui.HomeMemory
import io.github.quentinpn.viewer.tv.ui.HomeScreen
import io.github.quentinpn.viewer.tv.ui.SeriesScreen
import io.github.quentinpn.viewer.tv.ui.SignInScreen
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import kotlinx.coroutines.launch

/** Launcher: sign-in when no session is stored, else the home shelves and the series pages. */
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
                    var series by remember(current) { mutableStateOf<String?>(null) }
                    val home = remember(current) { HomeMemory() }
                    val refresh by AppServices.reporter.stopped.collectAsState()
                    val play = { itemId: String, startTicks: Long ->
                        startActivity(
                            Intent(this, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId)
                                .putExtra(PlayerActivity.EXTRA_START_TICKS, startTicks),
                        )
                    }
                    val expired = {
                        store.clear()
                        notice = "Session expirée. Reconnectez-vous."
                        session = null
                    }
                    val openSeries = series
                    if (openSeries != null) {
                        BackHandler { series = null }
                        SeriesScreen(client, current.userId, openSeries, images, onPlay = play, onSessionExpired = expired, refresh = refresh)
                        return@ViewerTheme
                    }
                    HomeScreen(
                        client = client,
                        userId = current.userId,
                        userName = current.userName,
                        images = images,
                        onPlay = play,
                        onOpenSeries = { series = it },
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
