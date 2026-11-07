// Chapter navigation of the player (ADR 0034): the current chapter, where the
// previous and next chapter keys go, and the marks on the seek bar.
import type { Chapter } from "../emby/types";

/**
 * Past this far into a chapter, "previous" goes back to its start; closer to it,
 * to the start of the chapter before, as in most players.
 */
export const RESTART_SECONDS = 3;
/** A seek lands about on the start: a position this close counts as in it. */
const LANDING_SLACK_SECONDS = 0.5;

/** Index of the chapter playing at `position`, or -1 before the first one. */
export function chapterIndex(chapters: Chapter[], position: number): number {
  let current = -1;
  for (const [i, c] of chapters.entries()) {
    if (c.start <= position + LANDING_SLACK_SECONDS) current = i;
    else break;
  }
  return current;
}

/**
 * Where the previous (`-1`) or next (`1`) chapter key seeks to, in seconds;
 * `null` when there is nowhere to go (no chapters, or after the last one).
 */
export function chapterTarget(
  chapters: Chapter[],
  position: number,
  delta: -1 | 1,
): number | null {
  const i = chapterIndex(chapters, position);
  if (delta === 1) return chapters[i + 1]?.start ?? null;
  if (i < 0) return chapters.length > 0 ? 0 : null;
  const current = chapters[i];
  if (!current) return null;
  if (position - current.start > RESTART_SECONDS || i === 0)
    return current.start;
  return chapters[i - 1]?.start ?? current.start;
}

/**
 * Positions of the marks on the seek bar, in percent of the duration. The start
 * of the file and anything past the end get none.
 */
export function chapterMarks(chapters: Chapter[], duration: number): number[] {
  if (!(duration > 0)) return [];
  return chapters
    .filter((c) => c.start > 0 && c.start < duration)
    .map((c) => (c.start / duration) * 100);
}
