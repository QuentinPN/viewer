package io.github.quentinpn.viewer.tv.ui.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.Centered
import io.github.quentinpn.viewer.tv.ui.Palette
import io.github.quentinpn.viewer.tv.request.RequestRepository
import io.github.quentinpn.viewer.tv.request.RequestState

/** The tabs of the Demander screen, in the order of the segments. */
internal enum class RequestsSegment(val label: String) {
    Discover("Découvrir"),
    Films("Films"),
    Series("Séries"),
    Mine("Mes demandes"),
}

/**
 * State of the Demander tab that outlives a film or series page opened from it (« Lire »), so
 * Back comes back to the same segment. Everything is read again when the tab is opened anew.
 */
class RequestsMemory {
    internal var segment by mutableStateOf(RequestsSegment.Discover)

    /** Bumped after a request or a cancellation: what shows requests (badges, strip, count) is read again. */
    internal var revision by mutableIntStateOf(0)
}

/**
 * Demander (ADR 0125): the setup states until Request is usable, then its segments. Back
 * closes the tab; the levels inside it (a title, a request) handle Back before it.
 */
@Composable
fun RequestsScreen(repository: RequestRepository, memory: RequestsMemory, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val access = rememberRequestsAccess(repository)
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Text(
            "Demander",
            style = MaterialTheme.typography.headlineMedium,
            color = Palette.text,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp),
        )
        val status = access.status
        when {
            status == null -> Centered("Connexion à Request…")
            status.state == RequestState.Ready -> Text(
                stateLine(status),
                color = Palette.textSecondary,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
            else -> RequestsSetupTv(status, access)
        }
    }
}
