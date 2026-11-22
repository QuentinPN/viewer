import type { WatchItem } from "../emby/types";
import { formatMinutes } from "../format/duration";

// Labels of the « Playlists » row and of a playlist's page, as on
// the TV.

/** "1 titre", "12 titres". */
export function titlesLabel(count: number): string {
  return count === 1 ? "1 titre" : `${count} titres`;
}

/**
 * "3 titres · 4 h 12": the count, with the total length only when every entry
 * has one, since a partial sum would read as the whole.
 */
export function playlistSummary(entries: WatchItem[]): string {
  const count = titlesLabel(entries.length);
  if (entries.length === 0) return count;
  let total = 0;
  for (const e of entries) {
    if (e.runtimeMinutes == null || e.runtimeMinutes <= 0) return count;
    total += e.runtimeMinutes;
  }
  const length = formatMinutes(total);
  return length ? `${count} · ${length}` : count;
}

/** The entry « Tout lire » starts: the first one, from its resume point. */
export function firstEntry(entries: WatchItem[]): WatchItem | null {
  return entries[0] ?? null;
}
