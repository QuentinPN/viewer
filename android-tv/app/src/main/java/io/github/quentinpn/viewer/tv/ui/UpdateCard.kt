package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** What the card says: the update itself, or why Android's settings open next. */
enum class UpdateCardMode { Ready, Permission, Installing }

/**
 * The update card of the home: centred glass over the shelves, « Installer » white
 * and focused first, « Plus tard » in glass. Back is « Plus tard ».
 */
@Composable
fun UpdateCard(
    versionName: String,
    notes: String,
    mode: UpdateCardMode,
    error: String?,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    val install = remember { FocusRequester() }
    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(520.dp)
                    .background(CardGlass, RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 36.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Mise à jour $versionName prête", style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                val subtitle = when (mode) {
                    UpdateCardMode.Ready -> "Téléchargée. Elle s'installe quand tu le décides."
                    UpdateCardMode.Permission ->
                        "Android doit d'abord autoriser Viewer à installer des mises à jour. Active l'option pour Viewer sur l'écran suivant. Android ferme alors l'app : rouvre-la et choisis de nouveau « Installer »."
                    UpdateCardMode.Installing -> "Ouverture de l'installateur d'Android…"
                }
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Palette.textSecondary)
                if (mode == UpdateCardMode.Ready && notes.isNotBlank()) {
                    Text(notes, style = MaterialTheme.typography.bodyMedium, color = Palette.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.error) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CardButton(
                        if (mode == UpdateCardMode.Permission) "Continuer" else "Installer",
                        primary = true,
                        enabled = mode != UpdateCardMode.Installing,
                        modifier = Modifier.focusRequester(install),
                        onClick = onInstall,
                    )
                    CardButton("Plus tard", primary = false, enabled = true, onClick = onLater)
                }
            }
        }
    }
    LaunchedEffect(mode) { runCatching { install.requestFocus() } }
}

private val CardGlass = Color(0xEB1C1C1E)

@Composable
private fun CardButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val still = reducedMotion()
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Palette.text else Glass,
            contentColor = if (primary) Color.Black else Palette.text,
            focusedContainerColor = Palette.text,
            focusedContentColor = Color.Black,
            pressedContainerColor = Palette.text,
            pressedContentColor = Color.Black,
            disabledContainerColor = Glass.copy(alpha = 0.12f),
            disabledContentColor = Palette.textSecondary.copy(alpha = 0.45f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.06f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), inset = -RING_GAP, shape = CircleShape)),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
