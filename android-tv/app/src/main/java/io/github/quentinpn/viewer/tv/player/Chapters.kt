package io.github.quentinpn.viewer.tv.player

import io.github.quentinpn.viewer.tv.emby.ChapterInfo

// Chapters of the player (ADR 0034, 0057), as src-tauri/src/emby/chapters.rs and
// src/lib/player/chapters.ts: Emby's `Chapters`, kept to the regular `Chapter`
// markers. The intro and credits markers stay with the segments (ADR 0048).

private const val TICKS_PER_SECOND = 10_000_000.0

/** Chapters closer than this to the previous one are dropped: one row would skip nothing. */
private const val MIN_GAP_SECONDS = 1.0

/** Names are shown on one line; anything longer is cut. */
private const val MAX_NAME_CHARS = 120

/** A seek lands about on the start: a position this close counts as in it. */
private const val LANDING_SLACK_SECONDS = 0.5

private val SPACES = Regex("(?U)\\s+")

/** A chapter, from [start] seconds of the file. */
data class Chapter(val start: Double, val name: String)

/**
 * The chapters the player offers, in order: sorted, within the file, one per
 * second at most, an empty name becoming « Chapitre N ». Fewer than two give
 * none: a single chapter is the whole file and offers no navigation.
 */
fun chapters(infos: List<ChapterInfo>, runTimeTicks: Long?): List<Chapter> {
    val end = runTimeTicks?.takeIf { it > 0 }?.let { it / TICKS_PER_SECOND }
    val found = infos
        .filter { it.markerType == null || it.markerType == "Chapter" }
        .map { it.startPositionTicks / TICKS_PER_SECOND to it.name }
        .filter { (start, _) -> start >= 0 && (end == null || start < end) }
        .sortedBy { it.first }
    val kept = mutableListOf<Pair<Double, String?>>()
    for (chapter in found) {
        if (kept.isEmpty() || chapter.first - kept.last().first >= MIN_GAP_SECONDS) kept += chapter
    }
    if (kept.size < 2) return emptyList()
    return kept.mapIndexed { i, (start, name) -> Chapter(start, cleanName(name) ?: "Chapitre ${i + 1}") }
}

/** One line of printable text, or null when nothing is left. */
private fun cleanName(name: String?): String? {
    val line = name?.trim()?.split(SPACES)?.joinToString(" ")?.filterNot { it.isISOControl() } ?: return null
    // Cut by characters, never inside a surrogate pair
    val cut = if (line.codePointCount(0, line.length) > MAX_NAME_CHARS) line.substring(0, line.offsetByCodePoints(0, MAX_NAME_CHARS)) else line
    return cut.ifEmpty { null }
}

/** Index of the chapter playing at [position], or -1 before the first one. */
fun chapterIndex(chapters: List<Chapter>, position: Double): Int =
    chapters.indexOfLast { it.start <= position + LANDING_SLACK_SECONDS }
