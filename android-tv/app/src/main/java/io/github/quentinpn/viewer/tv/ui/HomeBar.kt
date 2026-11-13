package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Entries of the home bar (ADR 0064); the one opened last takes the focus back on return.
 * [Library] is one of the libraries, named by [HomeBar]'s `focusLibrary` (ADR 0092).
 */
internal enum class BarItem { Home, Library, Search, Settings, Account }

/**
 * The library « Films » or « Séries » opens: the one named so when there are several of
 * [itemTypes] (`Movie` or `Series`), else the first one; none hides the entry.
 */
internal fun sectionLibrary(libraries: List<Library>, itemTypes: String, label: String): Library? {
    val candidates = libraries.filter { it.itemTypes == itemTypes }
    return candidates.firstOrNull { it.name.trim().equals(label, ignoreCase = true) } ?: candidates.firstOrNull()
}

/** The bar's libraries (ADR 0092): every film and series library, in the server's order, as the desktop lists them. */
internal fun barLibraries(libraries: List<Library>): List<Library> =
    libraries.filter { it.itemTypes == "Movie" || it.itemTypes == "Series" }.distinctBy { it.id }

/** The avatar's letter: the user name's first letter or digit, upper case. */
internal fun initial(userName: String): String = userName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** Translucent fill of the pill and of the active entry while the bar has the focus. */
private val pill = Color(0x9E2C2C30)
private val activeInFocusedBar = Color(0x2EFFFFFF)
private val idleText = Color(0xD1EBEBF5)

/**
 * The home's floating bar (ADR 0064): Accueil, each film and series library by its name
 * (ADR 0092), the search and settings icons, then the avatar, whose menu holds « Se
 * déconnecter ». [home] is the focus target of Up from the hero; Down from the bar goes to
 * [below]. Too many libraries for the screen scroll the bar sideways.
 */
@Composable
internal fun HomeBar(
    userName: String,
    /** Under the user's name in the account menu: « HomeNAS · non chiffré ». */
    serverLine: String,
    libraries: List<Library>,
    home: FocusRequester,
    below: FocusRequester?,
    /** The entry whose screen was opened last: the focus goes back to it. */
    focusOn: BarItem?,
    /** The library opened last, when [focusOn] is [BarItem.Library]. */
    focusLibrary: String?,
    onOpen: (BarItem) -> Unit,
    onOpenLibrary: (Library) -> Unit,
    onSignOut: () -> Unit,
    /** Back to the server shelf, the session kept for later (ADR 0104). */
    onSwitchServer: () -> Unit,
) {
    var barFocused by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(false) }
    val requesters = remember { BarItem.entries.associateWith { FocusRequester() } }
    val libraryRequesters = remember(libraries) { libraries.associate { it.id to FocusRequester() } }

    LaunchedEffect(focusOn, focusLibrary, libraryRequesters) {
        val target = when (focusOn) {
            null -> null
            BarItem.Library -> focusLibrary?.let(libraryRequesters::get)
            else -> requesters[focusOn]
        } ?: return@LaunchedEffect
        withFrameNanos {}
        runCatching { target.requestFocus() }
    }

    fun Modifier.below(): Modifier = focusProperties { if (below != null) down = below }

    fun Modifier.entry(item: BarItem): Modifier {
        val own = requesters.getValue(item)
        val base = if (item == BarItem.Home) focusRequester(home) else this
        return base.focusRequester(own).below()
    }

    Box(Modifier.fillMaxWidth().padding(top = 20.dp, start = 48.dp, end = 48.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .onFocusChanged { barFocused = it.hasFocus }
                .background(pill, CircleShape)
                .border(1.dp, Color(0x14FFFFFF), CircleShape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarEntry(Modifier.entry(BarItem.Home), active = true, barFocused = barFocused, onClick = {}) { color ->
                Label("Accueil", color, bold = true)
            }
            for (library in libraries) {
                val own = libraryRequesters.getValue(library.id)
                BarEntry(Modifier.focusRequester(own).below(), onClick = { onOpenLibrary(library) }) { color -> Label(library.name.trim(), color) }
            }
            BarEntry(Modifier.entry(BarItem.Search).semantics { contentDescription = "Rechercher" }, round = true, onClick = { onOpen(BarItem.Search) }) { color ->
                Icon(SearchIcon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            }
            BarEntry(Modifier.entry(BarItem.Settings).semantics { contentDescription = "Réglages" }, round = true, onClick = { onOpen(BarItem.Settings) }) { color ->
                Icon(SettingsIcon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            }
            Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(16.dp).background(Color(0x29FFFFFF)))
            Box {
                BarEntry(
                    Modifier.entry(BarItem.Account).semantics { contentDescription = "Compte : $userName" },
                    round = true,
                    idle = Palette.surfaceRaised,
                    onClick = { account = true },
                ) { color -> Label(initial(userName), color, bold = true) }
                if (account) AccountMenu(userName, serverLine, onDismiss = { account = false }, onSignOut = onSignOut, onSwitchServer = onSwitchServer)
            }
        }
    }
}

/**
 * One entry: white and slightly larger with an accent ring when focused. The [active] one
 * is plain white while the bar has no focus, and only lightly filled while it does.
 */
@Composable
private fun BarEntry(
    modifier: Modifier,
    active: Boolean = false,
    barFocused: Boolean = false,
    round: Boolean = false,
    idle: Color = Color.Transparent,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        active && !barFocused -> Palette.text
        active -> activeInFocusedBar
        else -> idle
    }
    val textColor = when {
        focused || (active && !barFocused) -> Color.Black
        active -> Palette.text
        else -> idleText
    }
    Surface(
        onClick = onClick,
        modifier = modifier.height(28.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = textColor,
            focusedContainerColor = Palette.text,
            focusedContentColor = Color.Black,
            pressedContainerColor = Palette.text,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), inset = (-2).dp, shape = CircleShape)),
    ) {
        Box(
            (if (round) Modifier.width(28.dp) else Modifier.padding(horizontal = 15.dp)).height(28.dp),
            contentAlignment = Alignment.Center,
        ) { content(textColor) }
    }
}

@Composable
private fun Label(text: String, color: Color, bold: Boolean = false) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
}

/** The server line of the account menu, as on Windows: its name, and « non chiffré » over HTTP. */
internal fun accountServerLine(serverName: String, https: Boolean): String =
    if (https) serverName else "$serverName · non chiffré"

/** Under the avatar: who is signed in and on which server, « Changer de serveur » and « Se déconnecter »; Back closes it. */
@Composable
private fun AccountMenu(userName: String, serverLine: String, onDismiss: () -> Unit, onSignOut: () -> Unit, onSwitchServer: () -> Unit) {
    val first = remember { FocusRequester() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, 80),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.background(Palette.surface, RoundedCornerShape(12.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(userName, color = Palette.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(serverLine, color = Palette.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            // The session stays saved: its tile on the shelf opens it again without a password (ADR 0104)
            OutlinedButton(onClick = onSwitchServer, modifier = Modifier.focusRequester(first)) { Text("Changer de serveur") }
            OutlinedButton(onClick = onSignOut) { Text("Se déconnecter") }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
}

/** Stroked 24-unit icons, as drawn in the mock-up. */
private fun strokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

internal val SearchIcon = strokeIcon("search", "M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0z", "M20 20l-3.5-3.5")

private val SettingsIcon = strokeIcon(
    "settings",
    "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1" +
        "a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3" +
        "a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9" +
        "a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1" +
        "a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
)

/** Filled triangle of the hero's play button. */
internal val PlayIcon: ImageVector = ImageVector.Builder("play", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(7f, 4.5f)
        verticalLineToRelative(15f)
        lineToRelative(12.5f, -7.5f)
        close()
    }
}.build()
