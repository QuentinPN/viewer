package io.github.quentinpn.viewer.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.DeviceIdentity
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.session.Reconnect
import io.github.quentinpn.viewer.tv.session.SavedServer
import io.github.quentinpn.viewer.tv.session.SavedServerRepository
import io.github.quentinpn.viewer.tv.session.Session
import io.github.quentinpn.viewer.tv.session.hostLabel
import io.github.quentinpn.viewer.tv.session.reconnect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Where the pre-connection screen stands (ADR 0104); the cards open over the shelf. */
internal sealed interface ShelfStep {
    data object Shelf : ShelfStep

    /** « Gérer »: a tile asks to forget its server. */
    data object Manage : ShelfStep

    /** OK on a tile: its saved token is being checked. */
    data class Connecting(val key: String) : ShelfStep

    /** The check failed for another reason than a refused token. */
    data class Failed(val key: String, val error: EmbyError) : ShelfStep

    /** « Session expirée »: the user name kept, the password asked. */
    data class Reauth(val key: String) : ShelfStep

    /** « Oublier « X » ? », « Annuler » focused. */
    data class Confirm(val key: String) : ShelfStep

    data object Manual : ShelfStep
}

/** Back goes up one level; null on the shelf itself, where Back leaves the app. */
internal fun backFrom(step: ShelfStep): ShelfStep? = when (step) {
    ShelfStep.Shelf -> null
    is ShelfStep.Confirm -> ShelfStep.Manage
    ShelfStep.Manage, ShelfStep.Manual, is ShelfStep.Connecting, is ShelfStep.Failed, is ShelfStep.Reauth -> ShelfStep.Shelf
}

/** After forgetting a server: still in « Gérer » while tiles remain. */
internal fun afterForget(remaining: Int): ShelfStep = if (remaining > 0) ShelfStep.Manage else ShelfStep.Shelf

/** The step the screen opens on: « Session expirée » for a session refused while in use, else the shelf. */
internal fun firstStep(reauthKey: String?, servers: List<SavedServer>): ShelfStep =
    if (reauthKey != null && servers.any { it.key == reauthKey }) ShelfStep.Reauth(reauthKey) else ShelfStep.Shelf

internal fun shelfMessage(manage: Boolean, count: Int): String = when {
    manage -> "Choisissez le serveur à oublier."
    count > 0 -> "Qui regarde ? Choisissez un serveur."
    else -> "Bienvenue. Ajoutez votre premier serveur Emby."
}

/** Under a tile's name: who signs in, and where. */
internal fun tileSubtitle(server: SavedServer) = "${server.userName} · ${hostLabel(server.server)}"

/** The big letter of a tile: the first letter or digit of its name. */
internal fun tileInitial(server: SavedServer): String =
    server.displayName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** A stable hue per tile, so each server keeps its colour. */
internal fun tileHue(key: String): Float = (((key.hashCode() % 360) + 360) % 360).toFloat()

internal fun failureTitle(error: EmbyError): String =
    if (error == EmbyError.Unreachable) "Serveur injoignable" else "Connexion impossible"

private val TILE_WIDTH = 184.dp
private val Danger = Color(0xFFFF6961)
private val Line = Color(0x1FFFFFFF)

/**
 * The signed-out screen, variant B « L'étagère » (ADR 0104): the logo, one 16:10 tile per saved
 * server, then « Se connecter manuellement » and « Gérer ». OK on a tile checks its saved token;
 * a refused one opens « Session expirée ». Forgetting asks first, « Annuler » focused.
 */
@Composable
fun PreConnectionScreen(
    store: SavedServerRepository,
    device: DeviceIdentity,
    http: OkHttpClient,
    /** A session refused while in use: its « Session expirée » card opens first. */
    reauthKey: String?,
    onSignedIn: (Session) -> Unit,
    /** Best-effort sign-out on the server of a forgotten tile, never waited for. */
    onRevoke: (SavedServer, String) -> Unit,
) {
    var servers by remember { mutableStateOf(store.servers()) }
    var step by remember { mutableStateOf(firstStep(reauthKey, servers)) }
    // The tile the focus comes back to, and whether « Gérer » was just opened (« Terminer » focused)
    var lastKey by remember { mutableStateOf(reauthKey ?: servers.firstOrNull()?.key) }
    var focusDone by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val tileFocus = remember(servers) { servers.associate { it.key to FocusRequester() } }
    val manualFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }

    fun back() {
        job?.cancel()
        job = null
        step = backFrom(step) ?: ShelfStep.Shelf
    }

    fun signedIn(done: SignedIn) {
        store.save(done.session, done.serverId, done.serverName)
        onSignedIn(done.session)
    }

    fun open(entry: SavedServer) {
        lastKey = entry.key
        if (step is ShelfStep.Connecting) return
        if (!entry.hasToken) {
            step = ShelfStep.Reauth(entry.key)
            return
        }
        step = ShelfStep.Connecting(entry.key)
        job = scope.launch {
            val token = store.token(entry.key)
            if (token == null) {
                servers = store.servers()
                step = ShelfStep.Reauth(entry.key)
                return@launch
            }
            when (val result = reconnect(entry, token, device, http)) {
                is Reconnect.Opened -> {
                    store.activate(entry.key, result.serverId, result.serverName)
                    onSignedIn(Session(entry.server, entry.userId, entry.userName, token))
                }
                Reconnect.Refused -> {
                    store.dropToken(entry.key)
                    servers = store.servers()
                    step = ShelfStep.Reauth(entry.key)
                }
                is Reconnect.Failed -> step = ShelfStep.Failed(entry.key, result.error)
            }
        }
    }

    val manage = step == ShelfStep.Manage || step is ShelfStep.Confirm
    // Focus once per view of the shelf: a card closing gives it back to its tile
    val view = when (step) {
        ShelfStep.Shelf, is ShelfStep.Connecting -> 0
        ShelfStep.Manage -> 1
        else -> 2
    }
    LaunchedEffect(view, servers) {
        if (view == 2) return@LaunchedEffect
        withFrameNanos {}
        val tile = lastKey?.let(tileFocus::get)
        val target = when {
            view == 1 && (focusDone || tile == null) -> doneFocus
            tile != null -> tile
            else -> servers.firstOrNull()?.key?.let(tileFocus::get) ?: manualFocus
        }
        runCatching { target.requestFocus() }
    }
    // The shelf itself leaves Back to the system; the cards close on their own Back
    BackHandler(enabled = step == ShelfStep.Manage || step is ShelfStep.Connecting) { back() }

    Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Brand("Viewer", shelfMessage(manage, servers.size), logo = 72, titleSize = 26)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    // Room for the focused tile's scale and ring
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    for (entry in servers) {
                        ServerTile(
                            entry,
                            manage = manage,
                            busy = (step as? ShelfStep.Connecting)?.key == entry.key,
                            modifier = Modifier.focusRequester(tileFocus.getValue(entry.key)),
                        ) {
                            if (manage) {
                                lastKey = entry.key
                                focusDone = false
                                step = ShelfStep.Confirm(entry.key)
                            } else {
                                open(entry)
                            }
                        }
                    }
                    if (servers.isEmpty()) GhostTile()
                }
            }
            when {
                manage -> ActionButton(
                    "Terminer",
                    { step = ShelfStep.Shelf },
                    Modifier.width(220.dp).focusRequester(doneFocus),
                    kind = ActionKind.Primary,
                )
                servers.isNotEmpty() -> Row(Modifier.width(420.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("Se connecter manuellement", { step = ShelfStep.Manual }, Modifier.weight(1.5f).focusRequester(manualFocus))
                    ActionButton("Gérer", {
                        focusDone = true
                        step = ShelfStep.Manage
                    }, Modifier.weight(1f))
                }
                else -> ActionButton(
                    "Se connecter manuellement",
                    { step = ShelfStep.Manual },
                    Modifier.width(300.dp).focusRequester(manualFocus),
                    kind = ActionKind.Primary,
                )
            }
        }
    }

    when (val current = step) {
        ShelfStep.Manual -> ShelfCard(::back) {
            SignInForm(
                title = "Connexion manuelle",
                message = "Saisissez l'adresse de votre serveur Emby et vos identifiants.",
                fixedServer = null,
                initialServer = store.lastServer,
                initialUser = "",
                device = device,
                http = http,
                onBack = ::back,
                onSignedIn = ::signedIn,
            )
        }
        is ShelfStep.Reauth -> servers.find { it.key == current.key }?.let { entry ->
            ShelfCard(::back) {
                SignInForm(
                    title = "Session expirée",
                    message = "Le serveur « ${entry.displayName} » demande à nouveau votre mot de passe.",
                    fixedServer = entry.server,
                    initialServer = null,
                    initialUser = entry.userName,
                    device = device,
                    http = http,
                    onBack = ::back,
                    onSignedIn = ::signedIn,
                )
            }
        }
        is ShelfStep.Failed -> servers.find { it.key == current.key }?.let { entry ->
            ShelfCard(::back) {
                val retry = remember { FocusRequester() }
                Brand(failureTitle(current.error), "« ${entry.displayName} » : ${current.error.message}", logo = 56)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Retour", ::back, Modifier.weight(1f))
                    ActionButton("Réessayer", {
                        step = ShelfStep.Shelf
                        open(entry)
                    }, Modifier.weight(1f).focusRequester(retry), kind = ActionKind.Primary)
                }
                FocusOnOpen(retry)
            }
        }
        is ShelfStep.Confirm -> servers.find { it.key == current.key }?.let { entry ->
            ShelfCard(::back) {
                val cancel = remember { FocusRequester() }
                Brand(
                    "Oublier « ${entry.displayName} » ?",
                    "L'adresse, le nom d'utilisateur et la session enregistrés sur cet appareil seront effacés. " +
                        "Rien n'est supprimé sur le serveur.",
                    logo = 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Focused first: a stray OK on the remote erases nothing
                    ActionButton("Annuler", ::back, Modifier.weight(1f).focusRequester(cancel))
                    ActionButton("Oublier ce serveur", {
                        store.forget(entry.key)?.let { onRevoke(entry, it) }
                        servers = store.servers()
                        lastKey = servers.firstOrNull()?.key
                        focusDone = true
                        step = afterForget(servers.size)
                    }, Modifier.weight(1.3f), kind = ActionKind.Danger)
                }
                FocusOnOpen(cancel)
            }
        }
        else -> Unit
    }
}

@Composable
private fun FocusOnOpen(target: FocusRequester) {
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { target.requestFocus() }
    }
}

/** A card over the dimmed shelf; Back closes it. */
@Composable
private fun ShelfCard(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(400.dp).background(Color(0xF2232328), shape).border(1.dp, Line, shape).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun ServerTile(entry: SavedServer, manage: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val still = reducedMotion()
    val hue = tileHue(entry.key)
    Column(Modifier.width(TILE_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 10f).semantics {
                contentDescription = (if (manage) "Oublier " else "Se connecter à ") + entry.displayName
            },
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = if (still) 1f else 1.06f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Palette.accent), inset = -RING_GAP, shape = shape)),
            colors = CardDefaults.colors(containerColor = Palette.surface),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color.hsl(hue, 0.35f, 0.30f), Color.hsl(hue, 0.30f, 0.12f))), shape)
                    .border(1.dp, Line, shape),
                contentAlignment = Alignment.Center,
            ) {
                // In « Gérer » the red layer replaces the initial, which would show through it
                if (!manage) Text(tileInitial(entry), color = Color.hsl(hue, 0.6f, 0.9f), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                val pill = when {
                    busy -> "Connexion…"
                    !entry.hasToken -> "Mot de passe requis"
                    else -> null
                }
                pill?.let {
                    Text(
                        it,
                        color = if (busy) Palette.textSecondary else Color(0xFFFFD18A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (manage) {
                    Box(Modifier.fillMaxSize().background(Danger.copy(alpha = 0.78f), shape), contentAlignment = Alignment.Center) {
                        Text("Oublier", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(entry.displayName, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tileSubtitle(entry), color = Palette.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** In place of the tiles when none is saved; not focusable. */
@Composable
private fun GhostTile() {
    Column(Modifier.width(TILE_WIDTH), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).drawBehind {
                drawRoundRect(
                    Color(0x33FFFFFF),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    cornerRadius = CornerRadius(14.dp.toPx()),
                )
            }.padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aucun serveur pour l'instant", color = Palette.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

internal enum class ActionKind { Glass, Primary, Danger }

/** A button of the shelf and its cards: glass, white (primary) or red (danger); focused, white with the accent ring. */
@Composable
internal fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ActionKind = ActionKind.Glass,
    enabled: Boolean = true,
) {
    val still = reducedMotion()
    val shape = RoundedCornerShape(10.dp)
    val container = when (kind) {
        ActionKind.Glass -> Glass
        ActionKind.Primary -> Palette.text
        ActionKind.Danger -> Danger
    }
    val content = if (kind == ActionKind.Glass) Palette.text else Color.Black
    val focused = if (kind == ActionKind.Danger) Danger else Palette.text
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = content,
            focusedContainerColor = focused,
            focusedContentColor = Color.Black,
            pressedContainerColor = focused,
            pressedContentColor = Color.Black,
            disabledContainerColor = container.copy(alpha = 0.25f),
            disabledContentColor = content.copy(alpha = 0.5f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.05f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), inset = -RING_GAP, shape = shape)),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
