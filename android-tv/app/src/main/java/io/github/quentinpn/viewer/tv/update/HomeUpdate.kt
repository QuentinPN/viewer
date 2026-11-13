package io.github.quentinpn.viewer.tv.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.quentinpn.viewer.tv.ui.UpdateCard
import io.github.quentinpn.viewer.tv.ui.UpdateCardMode
import kotlinx.coroutines.launch

/**
 * The update card on the home only (ADR 0112): shown once an APK is ready and not put off
 * with « Plus tard ». Without the « sources inconnues » permission, « Installer » first
 * explains, then « Continuer » opens Android's setting; MainActivity installs on return.
 */
@Composable
fun HomeUpdate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    var dismissed by remember { mutableIntStateOf(Updater.dismissedVersion) }
    var explaining by remember { mutableStateOf(false) }
    val ready = when (val s = state) {
        is UpdateState.Ready -> s
        is UpdateState.Installing -> s.ready
        is UpdateState.Error -> s.ready
        UpdateState.Idle -> null
    } ?: return
    if (ready.versionCode == dismissed) return
    val mode = when {
        state is UpdateState.Installing -> UpdateCardMode.Installing
        explaining -> UpdateCardMode.Permission
        else -> UpdateCardMode.Ready
    }
    UpdateCard(
        versionName = ready.versionName,
        notes = ready.notes,
        mode = mode,
        error = (state as? UpdateState.Error)?.message,
        onInstall = {
            when {
                Updater.canInstall(context) -> {
                    explaining = false
                    scope.launch { Updater.install(context) }
                }
                !explaining -> explaining = true
                else -> {
                    explaining = false
                    Updater.awaitingPermission = true
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
                        )
                    } catch (_: ActivityNotFoundException) {
                        // Some TV builds hide this screen: the generic one lists the apps
                        Updater.awaitingPermission = false
                        runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    }
                }
            }
        },
        onLater = {
            Updater.dismiss()
            dismissed = Updater.dismissedVersion
        },
    )
}
