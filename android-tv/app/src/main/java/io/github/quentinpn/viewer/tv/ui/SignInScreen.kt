package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.brand.rememberLogo
import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.ServerUrl
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.session.hostLabel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** A sign-in that worked: the session, and the server's id and name for its tile. */
class SignedIn(val session: Session, val serverId: String, val serverName: String)

/**
 * Signs in by user name: the server is probed without credentials, then the password goes in
 * the request body only. Throws [EmbyException] with the French message to show.
 */
internal suspend fun signIn(server: String, user: String, password: String, device: DeviceIdentity, http: OkHttpClient): SignedIn {
    val client = EmbyClient(ServerUrl.parse(server), device, http)
    val info = client.publicInfo()
    val result = client.authenticate(user.trim(), password)
    return SignedIn(Session(client.server.toString(), result.user.id, result.user.name, result.accessToken), info.id, info.serverName)
}

/**
 * The manual sign-in and « Session expirée » cards: logo, title, then address (or
 * the saved server, read-only, when [fixedServer] is set), user name and password, and
 * « Retour » / « Se connecter ». The password is only kept in this card's memory.
 */
@Composable
internal fun SignInForm(
    title: String,
    message: String,
    /** The saved address of « Session expirée »: signed in to, shown as its host, not editable. */
    fixedServer: String?,
    initialServer: String?,
    initialUser: String,
    device: DeviceIdentity,
    http: OkHttpClient,
    onBack: () -> Unit,
    onSignedIn: (SignedIn) -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(fixedServer ?: initialServer.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }

    fun submit() {
        if (busy || server.isBlank() || user.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                val done = signIn(server, user, password, device, http)
                password = ""
                onSignedIn(done)
            } catch (e: EmbyException) {
                error = e.error.message
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Brand(title, message, logo = 56)
        if (fixedServer != null) {
            ReadOnlyField("Serveur", hostLabel(fixedServer))
            Field("Nom d'utilisateur", user, { user = it }, KeyboardType.Text)
            Field("Mot de passe", password, { password = it }, KeyboardType.Password, Modifier.focusRequester(first), secret = true, onDone = ::submit)
        } else {
            Field("Adresse du serveur", server, { server = it }, KeyboardType.Uri, Modifier.focusRequester(first))
            Field("Nom d'utilisateur", user, { user = it }, KeyboardType.Text)
            Field("Mot de passe", password, { password = it }, KeyboardType.Password, secret = true, onDone = ::submit)
        }
        error?.let { Text(it, color = Palette.error, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Le mot de passe n'est pas enregistré. Seul le jeton de session est conservé, chiffré par le Keystore Android.",
            color = Palette.textSecondary,
            fontSize = 11.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Retour", onBack, Modifier.weight(1f))
            ActionButton(
                if (busy) "Connexion…" else "Se connecter",
                ::submit,
                Modifier.weight(1f),
                kind = ActionKind.Primary,
                enabled = !busy && server.isNotBlank() && user.isNotBlank(),
            )
        }
    }
}

/** The app's logo, a title and one line under it, centred. */
@Composable
internal fun Brand(title: String, message: String?, logo: Int, titleSize: Int = 22) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (logo > 0) Image(painterResource(rememberLogo()), contentDescription = null, modifier = Modifier.size(logo.dp))
        Text(title, color = Palette.text, fontSize = titleSize.sp, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        message?.let { Text(it, color = Palette.textSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String) {
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.textSecondary, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            color = Palette.text,
            fontSize = 18.sp,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(Palette.surface.copy(alpha = 0.6f), shape).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** A labelled text field; [secret] hides what is typed. */
@Composable
internal fun Field(
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
            textStyle = TextStyle(color = Palette.text, fontSize = 18.sp),
            cursorBrush = SolidColor(Palette.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = type,
                autoCorrectEnabled = false,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = modifier
                .fillMaxWidth()
                .background(Palette.surface, shape)
                .border(if (focused) 3.dp else 1.dp, if (focused) Palette.accent else Palette.surfaceRaised, shape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
