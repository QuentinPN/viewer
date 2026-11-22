package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer

/** Jump targets in the picker's order: `#` for digits and symbols, then A to Z. */
val LETTERS: List<Char> = listOf('#') + ('A'..'Z')

/**
 * The letter a title files under: its first character without accents, upper case, so
 * « Été » goes to E and « Œdipe » to O; digits, symbols and other scripts go to `#`.
 */
fun letterOf(name: String): Char {
    val first = name.trimStart().firstOrNull() ?: return '#'
    val base = when (first) {
        'Œ', 'œ' -> 'O'
        'Æ', 'æ' -> 'A'
        'ß' -> 'S'
        else -> Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
    }
    val upper = base.uppercaseChar()
    return if (upper in 'A'..'Z') upper else '#'
}

/** The fields of one title the letter index needs: the server's `SortName`, articles dropped. */
@Serializable
data class SortNameDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("SortName") val sortName: String? = null,
) {
    /** What the server sorted on; the plain name when it sent none. */
    val key get() = sortName?.takeIf { it.isNotBlank() } ?: name
}

/** Where a letter starts in the grid's order: the first title's position on the server, and its id. */
data class LetterStart(val position: Int, val id: String)

/**
 * First title of each letter in [items], read in the grid's own order and filters, so a
 * position is the grid's own. Letters absent from the map have no title.
 */
fun letterStarts(items: List<SortNameDto>): Map<Char, LetterStart> {
    val starts = HashMap<Char, LetterStart>()
    items.forEachIndexed { position, item -> starts.getOrPut(letterOf(item.key)) { LetterStart(position, item.id) } }
    return starts
}

/** Items to have read so the page holding [position] is loaded: the end of that page. */
fun pageEnd(position: Int, pageSize: Int): Int = (position / pageSize + 1) * pageSize

/** Size of the next request: one page, or up to [wanted] in one go after a jump. */
fun pageLimit(fetched: Int, wanted: Int, pageSize: Int): Int = maxOf(pageSize, wanted - fetched)
