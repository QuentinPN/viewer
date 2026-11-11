package io.github.quentinpn.viewer.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.quentinpn.viewer.tv.emby.EmbyClient
import io.github.quentinpn.viewer.tv.emby.EmbyError
import io.github.quentinpn.viewer.tv.emby.EmbyException
import io.github.quentinpn.viewer.tv.emby.LETTERS
import io.github.quentinpn.viewer.tv.emby.LetterStart
import io.github.quentinpn.viewer.tv.emby.letterStarts

/** What TalkBack reads on a letter of the rail. */
internal fun letterDescription(letter: Char, available: Boolean): String = when {
    !available && letter == '#' -> "Aucun titre commençant par un chiffre ou un symbole"
    !available -> "Aucun titre à la lettre $letter"
    letter == '#' -> "Aller aux titres commençant par un chiffre ou un symbole"
    else -> "Aller à la lettre $letter"
}

/**
 * A letter can take the focus: it has a title in the index, or the index failed and OK reads
 * it again. While the index loads, every letter waits dimmed (ADR 0099).
 */
internal fun letterEnabled(letter: Char, letters: Map<Char, LetterStart>?, failed: Boolean): Boolean =
    letters?.containsKey(letter) ?: failed

/**
 * Vertical rail of `#` and A to Z beside the grid (ADR 0070, 0099), shown for a sort by
 * title. The index is one `SortName` query of the grid's own query, read when the rail first
 * shows for that query; a letter without a title is dimmed and skipped by the focus. OK on a
 * letter jumps the grid to its first title.
 */
@Composable
internal fun LetterRail(
    client: EmbyClient,
    userId: String,
    memory: LibraryMemory,
    modifier: Modifier,
    onSessionExpired: () -> Unit,
    onJump: (LetterStart) -> Unit,
) {
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt, memory.generation) {
        if (memory.letters != null) return@LaunchedEffect
        val generation = memory.generation
        try {
            val library = memory.library
            val names = client.sortNames(userId, library.id, library.itemTypes, memory.options)
            // Filters changed meanwhile: this index belongs to the old query
            if (generation == memory.generation) {
                memory.letters = letterStarts(names)
                failed = false
            }
        } catch (e: EmbyException) {
            if (e.error == EmbyError.SessionExpired) onSessionExpired()
            if (generation == memory.generation) failed = true
        }
    }
    val letters = memory.letters
    val still = reducedMotion()
    Column(modifier) {
        for (letter in LETTERS) {
            val target = letters?.get(letter)
            val enabled = letterEnabled(letter, letters, failed)
            val description = if (letters == null && failed) "Relire l'index des lettres" else letterDescription(letter, target != null)
            val picked = letter == memory.letter
            val shape = RoundedCornerShape(6.dp)
            Surface(
                onClick = {
                    when {
                        target != null -> {
                            memory.letter = letter
                            onJump(target)
                        }
                        letters == null -> {
                            failed = false
                            attempt++
                        }
                    }
                },
                enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = description },
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = if (picked) Palette.accent else Palette.textSecondary,
                    focusedContainerColor = Palette.text,
                    focusedContentColor = Color.Black,
                    pressedContainerColor = Palette.text,
                    pressedContentColor = Color.Black,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Palette.textSecondary.copy(alpha = 0.3f),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = if (still) 1f else 1.15f),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.accent), shape = shape)),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(letter.toString(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
