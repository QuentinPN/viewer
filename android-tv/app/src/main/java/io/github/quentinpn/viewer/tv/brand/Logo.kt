package io.github.quentinpn.viewer.tv.brand

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The drawable of the logo shown in the app: the season's, or the default one (see [logoSeason]). Read once per composition. */
@Composable
fun rememberLogo(): Int {
    val context = LocalContext.current
    return remember { LauncherIcon.logo(context) }
}
