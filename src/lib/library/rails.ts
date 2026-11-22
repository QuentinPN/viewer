// Letter rail, icon rail and counter of the compact library grid:
// letters, labels, states and jumps. Pure so it can be unit tested.
import type {
  ItemsOptions,
  LibraryKind,
  SortNameEntry,
  WatchFilter,
} from "../emby/types";
import {
  DEFAULT_OPTIONS,
  directionLabels,
  isDefault,
  SORTS,
  watchChoices,
} from "./options";

/** « # » for digits, symbols and other scripts, then A to Z. */
export const LETTERS: readonly string[] = [
  "#",
  ..."ABCDEFGHIJKLMNOPQRSTUVWXYZ",
];

/** Ligatures NFD does not split. */
const LIGATURES: Record<string, string> = {
  Œ: "O",
  Æ: "A",
  ß: "S",
  Ø: "O",
};

/** Letter a title is filed under: first character, no accent nor case. */
export function letterOf(name: string): string {
  const first = [...name.trim()][0];
  if (!first) return "#";
  const upper = first.toLocaleUpperCase("fr-FR");
  const folded =
    LIGATURES[upper] ?? upper.normalize("NFD").replace(/\p{M}/gu, "");
  return /^[A-Z]$/.test(folded) ? folded : "#";
}

/** Where a letter starts in the grid order. */
export interface LetterStart {
  /** Position in the server's order. */
  position: number;
  id: string;
}

/** First title of each letter present, in the order the grid shows. */
export function letterIndex(
  entries: readonly SortNameEntry[],
): Map<string, LetterStart> {
  const index = new Map<string, LetterStart>();
  entries.forEach((entry, position) => {
    const letter = letterOf(entry.name);
    if (!index.has(letter)) index.set(letter, { position, id: entry.id });
  });
  return index;
}

/**
 * Next letter that has a title from `from`, going `step` (±1), or `from` when
 * none. `available` null means every letter (index failed: all reachable).
 */
export function stepLetter(
  from: string,
  step: 1 | -1,
  available: ReadonlySet<string> | null,
): string {
  const start = LETTERS.indexOf(from);
  for (let i = start + step; i >= 0 && i < LETTERS.length; i += step) {
    const letter = LETTERS[i] as string;
    if (!available || available.has(letter)) return letter;
  }
  return from;
}

/** First (`edge` 1) or last (`edge` -1) letter that has a title. */
export function edgeLetter(
  edge: 1 | -1,
  available: ReadonlySet<string> | null,
): string | null {
  const list = edge === 1 ? LETTERS : [...LETTERS].reverse();
  return list.find((l) => !available || available.has(l)) ?? null;
}

export function letterLabel(letter: string, available: boolean): string {
  if (!available) {
    return letter === "#"
      ? "Aucun titre commençant par un chiffre ou un symbole"
      : `Aucun titre à la lettre ${letter}`;
  }
  return letter === "#"
    ? "Aller aux titres commençant par un chiffre ou un symbole"
    : `Aller à la lettre ${letter}`;
}

/**
 * How many items to load from `next` so the page holding `position` is in
 * the grid, at most `max` per request; 0 when it is already loaded.
 */
export function jumpLimit(
  position: number,
  next: number,
  pageSize: number,
  max: number,
): number {
  if (position < next) return 0;
  const pageEnd = Math.ceil((position + 1) / pageSize) * pageSize;
  return Math.min(max, pageEnd - next);
}

/** Réinitialiser shows when the sort, its direction or a filter differs. */
export const canReset = (o: ItemsOptions): boolean => !isDefault(o);

/** Drawn in accent: the sort is not Titre A → Z. */
export const sortActive = (o: ItemsOptions): boolean =>
  o.sort !== DEFAULT_OPTIONS.sort ||
  o.descending !== DEFAULT_OPTIONS.descending;

export const filtersActive = (o: ItemsOptions): boolean =>
  o.genre !== null || o.decade !== null;

export function sortLabel(o: ItemsOptions): string {
  const sort = SORTS.find((s) => s.key === o.sort)?.label ?? "Titre";
  const d = directionLabels(o.sort);
  return `Tri : ${sort}, ${o.descending ? d.descending : d.ascending}`;
}

export function watchLabel(kind: LibraryKind, watch: WatchFilter): string {
  const label = watchChoices(kind).find((w) => w.key === watch)?.label;
  return `Visionnage : ${label ?? "Tous"}`;
}

/** Next state of the Visionnage icon, in the order of `watchChoices`. */
export function nextWatch(kind: LibraryKind, watch: WatchFilter): WatchFilter {
  const keys = watchChoices(kind).map((w) => w.key);
  const i = keys.indexOf(watch);
  return keys[(i + 1) % keys.length] ?? "all";
}

export const favoritesLabel = (on: boolean): string =>
  `Favoris seulement : ${on ? "oui" : "non"}`;

export const decadeLabel = (decade: number): string => `années ${decade}`;

/** A line of the Années list. */
export const decadeChoice = (decade: number): string => `Années ${decade}`;

export function filtersLabel(o: ItemsOptions): string {
  const parts: string[] = [];
  if (o.genre !== null) parts.push(o.genre);
  if (o.decade !== null) parts.push(decadeLabel(o.decade));
  return `Filtres : ${parts.length ? parts.join(", ") : "aucun"}`;
}

/** Decades of a list of years, newest first, without repeats. */
export function decadesOf(years: readonly number[]): number[] {
  const decades = new Set(
    years.filter(Number.isInteger).map((y) => Math.floor(y / 10) * 10),
  );
  return [...decades].sort((a, b) => b - a);
}

/** « 12 | 239 », or nothing while the position or the total is unknown. */
export function counterLabel(
  index: number | null,
  total: number | null,
): string | null {
  if (index === null || total === null || total <= 0) return null;
  return `${Math.min(index + 1, total)} | ${total}`;
}

/**
 * Index of the first of `count` cards whose bottom is below `top`, `boxAt`
 * giving each card's top and height in grid order. Binary search: rows are
 * sorted, so a scroll reads a dozen boxes, not every card.
 */
export function firstVisible(
  count: number,
  boxAt: (i: number) => { top: number; height: number },
  top: number,
): number | null {
  let lo = 0;
  let hi = count;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    const box = boxAt(mid);
    if (box.top + box.height <= top) lo = mid + 1;
    else hi = mid;
  }
  return lo < count ? lo : null;
}
