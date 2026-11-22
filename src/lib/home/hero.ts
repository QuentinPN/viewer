// Pure logic of the home hero; unit tested in hero.test.ts.
import type { HeroItem } from "../emby/types";
import { formatMinutes } from "../format/duration";
import { formatTime } from "../player/format";
import { seasonEpisodeCode } from "./format";

/** Index after a step of `delta`, wrapping around both ends. */
export function stepIndex(
  index: number,
  delta: number,
  length: number,
): number {
  if (length <= 0) return 0;
  return (((index + delta) % length) + length) % length;
}

/**
 * Title to show once the list is read again: the one shown before if it is still
 * there, else the same place, within bounds.
 */
export function keptIndex(
  items: HeroItem[],
  shownId: string | null,
  shownIndex: number,
): number {
  const found = items.findIndex((i) => i.detail.id === shownId);
  if (found >= 0) return found;
  return Math.min(Math.max(shownIndex, 0), Math.max(items.length - 1, 0));
}

/** Label of the main button: `Reprendre à 12:34`, `Lecture`; `null` when there is nothing to play. */
export function playLabel(item: HeroItem): string | null {
  if (item.playId === null) return null;
  const position = item.resume?.positionSeconds ?? 0;
  return position > 0 ? `Reprendre à ${formatTime(position)}` : "Lecture";
}

/**
 * Watched share and `Reste 22 min` beside the bar, as on the TV; `null` without a
 * resume point or without a known length (the fraction gives it).
 */
export function heroRemaining(
  item: HeroItem,
): { fraction: number; label: string } | null {
  const resume = item.resume;
  if (!resume || resume.fraction == null) return null;
  const { positionSeconds, fraction } = resume;
  if (positionSeconds <= 0 || fraction <= 0 || fraction >= 1) return null;
  const left = positionSeconds / fraction - positionSeconds;
  return {
    fraction,
    label: `Reste ${formatMinutes(Math.max(1, left / 60))}`,
  };
}

/** Line under the title: `S4 · É1 · La Rançon`, or the year of a film or series. */
export function heroMeta(item: HeroItem): string {
  if (item.episode) {
    const { name, seasonIndex, index } = item.episode;
    return [seasonEpisodeCode(seasonIndex, index), name || null]
      .filter(Boolean)
      .join(" · ");
  }
  return item.detail.year != null ? String(item.detail.year) : "";
}

/** Accessible name of the main button: the visible label, then what it plays. */
export function playName(item: HeroItem): string | null {
  const label = playLabel(item);
  if (!label) return null;
  const meta = item.episode ? heroMeta(item) : "";
  return [`${label} ${item.detail.name}`, meta].filter(Boolean).join(", ");
}
