package io.github.quentinpn.viewer.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.ui.HomeScreen
import io.github.quentinpn.viewer.tv.ui.SignInScreen
import io.github.quentinpn.viewer.tv.ui.ViewerTheme
import kotlinx.coroutines.launch

/** Launcher: sign-in when no session is stored, else the home shelves. */
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
                    HomeScreen(
                        client = client,
                        userId = current.userId,
                        userName = current.userName,
                        images = images,
                        onPlay = { itemId, startTicks ->
                            startActivity(
                                Intent(this, PlayerActivity::class.java)
                                    .putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId)
                                    .putExtra(PlayerActivity.EXTRA_START_TICKS, startTicks),
                            )
                        },
                        onSessionExpired = {
                            store.clear()
                            notice = "Session expirée. Reconnectez-vous."
                            session = null
                        },
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
