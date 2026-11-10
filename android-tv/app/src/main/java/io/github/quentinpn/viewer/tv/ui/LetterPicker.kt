package io.github.quentinpn.viewer.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.LETTERS
import io.github.quentinpn.viewer.tv.emby.LetterStart
import io.github.quentinpn.viewer.tv.emby.letterStarts

/** What TalkBack reads on a letter of the picker. */
internal fun letterDescription(letter: Char, available: Boolean): String = when {
    !available && letter == '#' -> "Aucun titre commençant par un chiffre ou un symbole"
    !available -> "Aucun titre à la lettre $letter"
    letter == '#' -> "Aller aux titres commençant par un chiffre ou un symbole"
    else -> "Aller à la lettre $letter"
}

/**
 * Side panel of `#` and A to Z (ADR 0070), six to a row, beside the grid as the genre list.
 * The index is one `SortName` query of the grid's own query, read when the panel first
 * opens; a letter without a title is dimmed and skipped by the focus. Back closes it.
 */
@Composable
internal fun LetterPicker(
    client: EmbyClient,
    userId: String,
    memory: LibraryMemory,
    modifier: Modifier,
    onSessionExpired: () -> Unit,
    onDone: (LetterStart?) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    BackHandler { onDone(null) }
    LaunchedEffect(attempt) {
        if (memory.letters != null) return@LaunchedEffect
        val generation = memory.generation
        try {
            val library = memory.library
            val names = client.sortNames(userId, library.id, library.itemTypes, memory.options)
            // Filters changed meanwhile: this index belongs to the old query
            if (generation == memory.generation) memory.letters = letterStarts(names)
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            error = e.error.message
        }
    }
    val panel = modifier.fillMaxHeight().width(420.dp).background(Palette.surface)
    val letters = memory.letters
    val failed = error
    when {
        letters == null && failed != null -> Box(panel) {
            Retry(failed) {
                error = null
                attempt++
            }
        }
        letters == null -> Box(panel, contentAlignment = Alignment.Center) { Text("Chargement…", color = Palette.textSecondary) }
        else -> {
            val first = remember { FocusRequester() }
            // The letter picked last, else the first one with a title
            val start = memory.letter?.takeIf { it in letters } ?: LETTERS.firstOrNull { it in letters }
            Column(panel.padding(horizontal = 22.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Aller à la lettre", style = MaterialTheme.typography.titleLarge, color = Palette.text)
                if (letters.isEmpty()) Text("Aucun titre.", color = Palette.textSecondary)
                for (row in LETTERS.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (letter in row) {
                            val target = letters[letter]
                            val description = letterDescription(letter, target != null)
                            GlassButton(
                                onClick = {
                                    if (target != null) {
                                        memory.letter = letter
                                        onDone(target)
                                    }
                                },
                                modifier = (if (letter == start) Modifier.focusRequester(first) else Modifier)
                                    .width(54.dp)
                                    .semantics { contentDescription = description },
                                selected = letter == memory.letter,
                                height = 54.dp,
                                enabled = target != null,
                            ) {
                                Text(letter.toString(), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos {}
                runCatching { first.requestFocus() }
            }
        }
    }
}
