package io.github.quentinpn.viewer.tv.brand

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

/**
 * Time from [now] to the next midnight of its own zone, so a DST day (23 or 25 hours) is counted as it is.
 * At least one millisecond, so a loop waiting on it never spins.
 */
fun millisUntilNextMidnight(now: ZonedDateTime): Long =
    Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis().coerceAtLeast(1)

/**
 * The drawable of the logo shown in the app: the season's, or the default one (see [logoSeason]).
 * Follows the date while composed (next local midnight, return to the app) and the « Icônes saisonnières » setting.
 */
@Composable
fun rememberLogo(): Int {
    val context = LocalContext.current
    var logo by remember { mutableIntStateOf(LauncherIcon.logo(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { logo = LauncherIcon.logo(context) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextMidnight(ZonedDateTime.now()))
            logo = LauncherIcon.logo(context)
        }
    }
    DisposableEffect(context) {
        val store = SeasonalIconStore(context)
        // SharedPreferences keeps its listeners weakly: this reference is what keeps it alive until disposal.
        val listener = store.onChange { logo = LauncherIcon.logo(context) }
        onDispose { store.stopObserving(listener) }
    }
    return logo
}
