package io.github.quentinpn.viewer.tv.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.ui.GlassButton
import io.github.quentinpn.viewer.tv.ui.Palette

/** A centred card over the screen, on the pattern of the link picker; Back closes it. */
@Composable
internal fun DialogCard(title: String, onDismiss: () -> Unit, width: Dp = 460.dp, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(width).background(Palette.surface, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.text)
            content()
        }
    }
}

/** Takes the focus once laid out, one frame later. */
@Composable
internal fun FocusOnOpen(target: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        withFrameNanos {}
        runCatching { target.requestFocus() }
    }
}

/**
 * A question with « Annuler » and a confirming button. [focusConfirm] false starts on « Annuler »,
 * the safe answer for something that cannot be undone. A server [error] shows verbatim.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
    focusConfirm: Boolean = false,
) {
    val first = remember { FocusRequester() }
    DialogCard(title, onDismiss) {
        message?.let { Text(it, color = Palette.textSecondary) }
        error?.let { Text(it, color = Palette.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = if (focusConfirm) Modifier else Modifier.focusRequester(first)) { Text("Annuler") }
            Button(onClick = onConfirm, enabled = !busy, modifier = if (focusConfirm) Modifier.focusRequester(first) else Modifier) {
                Text(if (busy) "En cours…" else confirmLabel)
            }
        }
    }
    FocusOnOpen(first)
}

/**
 * The server's address, typed on the system keyboard (it opens with the card). The error of the
 * last attempt shows under the field; the card closes when [onSave] has succeeded, on the caller's side.
 */
@Composable
internal fun AddressDialog(
    initial: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    DialogCard("Adresse du serveur Request", onDismiss, width = 560.dp) {
        Text("Par exemple request.exemple.fr. Sans schéma, https:// est ajouté.", color = Palette.textSecondary)
        Box(Modifier.fillMaxWidth().background(Palette.surfaceRaised, CircleShape).padding(horizontal = 20.dp, vertical = 11.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Palette.text, fontSize = 18.sp),
                cursorBrush = SolidColor(Palette.text),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                // A text field keeps the D-pad for its cursor: Down goes on to the buttons
                modifier = Modifier.fillMaxWidth().focusRequester(field).onPreviewKeyEvent {
                    if (it.key == Key.DirectionDown && it.type == KeyEventType.KeyDown) {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    } else {
                        false
                    }
                },
            )
        }
        error?.let { Text(it, color = Palette.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDismiss) { Text("Annuler") }
            Button(onClick = { onSave(text) }, enabled = !busy && text.isNotBlank()) { Text(if (busy) "Connexion…" else confirmLabel) }
        }
    }
    FocusOnOpen(field)
}

/** A list to pick one entry from, opened on [selected]; OK picks and closes through [onPick]. */
@Composable
internal fun ChoiceDialog(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val current = remember { FocusRequester() }
    // A long list (the genres) opens around the current choice, which is then composed to take the focus
    val list = remember { LazyListState(firstVisibleItemIndex = (selected - 2).coerceAtLeast(0)) }
    DialogCard(title, onDismiss) {
        LazyColumn(
            Modifier.heightIn(max = 340.dp),
            state = list,
            contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(labels) { index, label ->
                GlassButton(
                    onClick = { onPick(index) },
                    selected = index == selected,
                    shape = RoundedCornerShape(10.dp),
                    modifier = (if (index == selected) Modifier.focusRequester(current) else Modifier).fillMaxWidth(),
                ) { Text(label, fontSize = 14.sp, maxLines = 1) }
            }
        }
    }
    FocusOnOpen(current)
}
