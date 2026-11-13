package io.github.quentinpn.viewer.tv.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.R
import io.github.quentinpn.viewer.tv.session.LostConnection
import io.github.quentinpn.viewer.tv.session.status
import kotlinx.coroutines.delay

/**
 * « Serveur injoignable » (ADR 0119): a calm full-screen state while the server is away. The
 * session is kept; automatic attempts run behind it. « Réessayer » is focused first;
 * « Changer de serveur » sets the session aside, token kept, and shows the shelf.
 */
@Composable
internal fun ConnectionLost(
    lost: LostConnection,
    serverName: String,
    serverLabel: String,
    onRetry: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    // Ticks the countdown once a second while an attempt is planned
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lost.retryAt) {
        while (lost.retryAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    Column(
        Modifier.fillMaxSize().background(Palette.background),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.icon), contentDescription = null, modifier = Modifier.size(88.dp))
        Text("Serveur injoignable", color = Palette.text, fontSize = 32.sp)
        Text(
            "Ta session est conservée. L'app réessaie toute seule et reprend là où tu étais.",
            color = Palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        Text(serverName, color = Palette.text)
        Text(serverLabel, color = Palette.textSecondary)
        Text(lost.error.message, color = Palette.error, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 640.dp))
        Text(
            lost.status(now),
            color = Palette.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 640.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Stays enabled while checking so the focus never jumps; the press is then ignored
            Button(onClick = { if (!lost.checking) onRetry() }, modifier = Modifier.focusRequester(retry)) { Text("Réessayer") }
            OutlinedButton(onClick = onChangeServer) { Text("Changer de serveur") }
        }
    }
}
