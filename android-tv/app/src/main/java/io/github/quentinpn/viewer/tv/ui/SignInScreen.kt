package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ServerUrl
import io.github.quentinpn.viewer.tv.session.Session
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Server address, user name and password; the password is only kept in this screen's memory. */
@Composable
fun SignInScreen(
    initialServer: String?,
    notice: String?,
    device: DeviceIdentity,
    http: OkHttpClient,
    onSignedIn: (Session) -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(initialServer.orEmpty()) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(notice) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val client = EmbyClient(ServerUrl.parse(server), device, http)
                client.publicInfo()
                val result = client.authenticate(user.trim(), password)
                password = ""
                onSignedIn(Session(client.server.toString(), result.user.id, result.user.name, result.accessToken))
            } catch (e: EmbyException) {
                error = e.error.message
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Viewer", style = MaterialTheme.typography.displaySmall, color = Palette.text)
            Text("Connexion au serveur Emby", style = MaterialTheme.typography.titleMedium, color = Palette.textSecondary)
            Field("Adresse du serveur", server, { server = it }, KeyboardType.Uri, Modifier.focusRequester(first))
            Field("Nom d'utilisateur", user, { user = it }, KeyboardType.Text)
            Field("Mot de passe", password, { password = it }, KeyboardType.Password, secret = true, onDone = ::submit)
            error?.let { Text(it, color = Palette.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = ::submit, enabled = !busy && server.isNotBlank() && user.isNotBlank()) {
                Text(if (busy) "Connexion…" else "Se connecter")
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.textSecondary, style = MaterialTheme.typography.labelLarge)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = Palette.text, fontSize = 20.sp),
            cursorBrush = SolidColor(Palette.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = type,
                autoCorrectEnabled = false,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = modifier
                .background(Palette.surface, shape)
                .border(if (focused) 3.dp else 1.dp, if (focused) Palette.accent else Palette.surfaceRaised, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .width(448.dp),
        )
    }
}
