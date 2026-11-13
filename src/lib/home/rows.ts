import type { Library } from "../emby/types";

// Order and visibility of the home rows (ADR 0113). Same stable keys as the
// Android TV app (ADR 0094): kept on this device per Emby server and user, never
// on the server. The hero is not a row.

export const RESUME_ROW = "resume";
export const NEXT_UP_ROW = "nextUp";
export const FAVORITES_ROW = "favorites";

/** By library id, so a renamed library keeps its place and choice. */
export const latestRowKey = (libraryId: string) => `latest.${libraryId}`;

export interface HomeRow {
  key: string;
  label: string;
  /** Set for a library's « Ajoutés récemment » row. */
  library: Library | null;
}

export interface HomeRowPrefs {
  /** Keys in the user's order; unknown keys are ignored. */
  order: string[];
  hidden: string[];
}

export const DEFAULT_PREFS: HomeRowPrefs = { order: [], hidden: [] };

/** Every row the home can show, in the default order. */
export function defaultRows(libraries: Library[]): HomeRow[] {
  return [
    { key: RESUME_ROW, label: "Reprendre", library: null },
    { key: NEXT_UP_ROW, label: "À suivre", library: null },
    { key: FAVORITES_ROW, label: "Favoris", library: null },
    ...libraries.map((library) => ({
      key: latestRowKey(library.id),
      label: `Ajoutés récemment · ${library.name}`,
      library,
    })),
  ];
}

/**
 * The rows in the stored order. Unknown keys are dropped; a row the order does
 * not know (a new library) goes right after the row that precedes it by default,
 * or first when none does.
 */
export function orderedRows(rows: HomeRow[], order: string[]): HomeRow[] {
  const byKey = new Map(rows.map((r) => [r.key, r]));
  const result: HomeRow[] = [];
  for (const key of order) {
    const row = byKey.get(key);
    if (row && !result.includes(row)) result.push(row);
  }
  rows.forEach((row, i) => {
    if (result.includes(row)) return;
    const before = i > 0 ? result.indexOf(rows[i - 1] as HomeRow) : -1;
    result.splice(before + 1, 0, row);
  });
  return result;
}

/** The rows the home shows and requests, in order. */
export function visibleRows(rows: HomeRow[], prefs: HomeRowPrefs): HomeRow[] {
  const hidden = new Set(prefs.hidden);
  return orderedRows(rows, prefs.order).filter((r) => !hidden.has(r.key));
}

export function isShown(prefs: HomeRowPrefs, key: string): boolean {
  return !prefs.hidden.includes(key);
}

export function toggled(prefs: HomeRowPrefs, key: string): HomeRowPrefs {
  return {
    ...prefs,
    hidden: isShown(prefs, key)
      ? [...prefs.hidden, key]
      : prefs.hidden.filter((k) => k !== key),
  };
}

/** Moves `key` by `delta` places within the full order; the order is then explicit. */
export function moved(
  rows: HomeRow[],
  prefs: HomeRowPrefs,
  key: string,
  delta: number,
): HomeRowPrefs {
  const keys = orderedRows(rows, prefs.order).map((r) => r.key);
  const from = keys.indexOf(key);
  if (from < 0) return prefs;
  const to = Math.max(0, Math.min(keys.length - 1, from + delta));
  if (to === from) return prefs;
  keys.splice(from, 1);
  keys.splice(to, 0, key);
  return { ...prefs, order: keys };
}

/** Moves `key` to the place of `target` (drag and drop). */
export function movedTo(
  rows: HomeRow[],
  prefs: HomeRowPrefs,
  key: string,
  target: string,
): HomeRowPrefs {
  const keys = orderedRows(rows, prefs.order).map((r) => r.key);
  const from = keys.indexOf(key);
  const to = keys.indexOf(target);
  if (from < 0 || to < 0) return prefs;
  return moved(rows, prefs, key, to - from);
}

export const prefsKey = (serverId: string, userId: string) =>
  `viewer.home-rows.v1:${serverId}:${userId}`;

const strings = (v: unknown): string[] =>
  Array.isArray(v) ? v.filter((s): s is string => typeof s === "string") : [];

/** Storage may be missing, throw or hold garbage: the defaults then apply. */
export function readPrefs(storage: Storage | null, key: string): HomeRowPrefs {
  try {
    const raw = storage?.getItem(key);
    if (!raw) return DEFAULT_PREFS;
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) return DEFAULT_PREFS;
    const p = parsed as Record<string, unknown>;
    return { order: strings(p.order), hidden: strings(p.hidden) };
  } catch {
    return DEFAULT_PREFS;
  }
}

export function savePrefs(
  storage: Storage | null,
  key: string,
  prefs: HomeRowPrefs,
): void {
  try {
    storage?.setItem(key, JSON.stringify(prefs));
  } catch {
    // Quota or refused storage: the choice lasts until the app closes.
  }
}
