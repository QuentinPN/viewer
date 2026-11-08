import type { LibraryKind, MediaItem } from "../emby/types";
import { formatMinutes } from "../format/duration";

/** Items per request: a few rows on a large window, about 0.2–0.5 s on the server. */
export const PAGE_SIZE = 60;

/** A page as Rust returns it (`ItemPage`, `EpisodePage`). */
export interface Page<T> {
  items: T[];
  startIndex: number;
  nextIndex: number;
  total: number;
}

/** Pages of one list, loaded in order. Pure so it can be unit tested. */
export interface PagerState<T extends { id: string } = MediaItem> {
  items: T[];
  /** Server index of the next page. */
  next: number;
  /** Total announced by the server, `null` before the first page. */
  total: number | null;
  done: boolean;
}

export const emptyPager = <
  T extends { id: string } = MediaItem,
>(): PagerState<T> => ({
  items: [],
  next: 0,
  total: null,
  done: false,
});

/**
 * Appends a page. Emby 4.10 answers `TotalRecordCount: 0` past the end, so the
 * first total is kept; an empty page also ends the list. A page that does not
 * start where the list ends (stale or duplicate answer) is ignored.
 */
export function appendPage<T extends { id: string }>(
  state: PagerState<T>,
  page: Page<T>,
): PagerState<T> {
  if (state.done || page.startIndex !== state.next) return state;
  const seen = new Set(state.items.map((i) => i.id));
  const items = state.items.concat(page.items.filter((i) => !seen.has(i.id)));
  const total =
    state.total === null || page.total > 0 ? page.total : state.total;
  const next = Math.max(page.nextIndex, page.startIndex);
  const done = next === page.startIndex || next >= total;
  return { items, next, total, done };
}

/**
 * Puts fresh copies of loaded items in place (played mark, episode counts after a
 * playback). Order, membership and paging stay: the grid and its scroll do not move.
 */
export function refreshItems<T extends { id: string }>(
  state: PagerState<T>,
  fresh: T[],
): PagerState<T> {
  const byId = new Map(fresh.map((i) => [i.id, i]));
  if (!state.items.some((i) => byId.has(i.id))) return state;
  return { ...state, items: state.items.map((i) => byId.get(i.id) ?? i) };
}

/** "2020 · 1 h 41" style subtitle for a card. */
export function itemSubtitle(item: MediaItem): string {
  const parts: string[] = [];
  if (item.year) parts.push(String(item.year));
  const runtime = formatMinutes(item.runtimeMinutes);
  if (runtime) parts.push(runtime);
  return parts.join(" · ");
}

/** French count label for a library header. */
export function countLabel(total: number, kind: LibraryKind): string {
  const noun = kind === "movies" ? "film" : "série";
  const n = total.toLocaleString("fr-FR");
  return `${n} ${noun}${total > 1 ? "s" : ""}`;
}
