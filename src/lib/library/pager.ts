import type { ItemPage, LibraryKind, MediaItem } from "../emby/types";

/** Items per request: a few rows on a large window, about 0.2–0.5 s on the NAS. */
export const PAGE_SIZE = 60;

/** Pages of one library, loaded in order. Pure so it can be unit tested. */
export interface PagerState {
  items: MediaItem[];
  /** Server index of the next page. */
  next: number;
  /** Total announced by the server, `null` before the first page. */
  total: number | null;
  done: boolean;
}

export const emptyPager = (): PagerState => ({
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
export function appendPage(state: PagerState, page: ItemPage): PagerState {
  if (state.done || page.startIndex !== state.next) return state;
  const seen = new Set(state.items.map((i) => i.id));
  const items = state.items.concat(page.items.filter((i) => !seen.has(i.id)));
  const total =
    state.total === null || page.total > 0 ? page.total : state.total;
  const next = Math.max(page.nextIndex, page.startIndex);
  const done = next === page.startIndex || next >= total;
  return { items, next, total, done };
}

/** "2020 · 1 h 41" style subtitle for a card. */
export function itemSubtitle(item: MediaItem): string {
  const parts: string[] = [];
  if (item.year) parts.push(String(item.year));
  if (item.runtimeMinutes) {
    const h = Math.floor(item.runtimeMinutes / 60);
    const m = item.runtimeMinutes % 60;
    parts.push(h > 0 ? `${h} h ${String(m).padStart(2, "0")}` : `${m} min`);
  }
  return parts.join(" · ");
}

/** French count label for a library header. */
export function countLabel(total: number, kind: LibraryKind): string {
  const noun = kind === "movies" ? "film" : "série";
  const n = total.toLocaleString("fr-FR");
  return `${n} ${noun}${total > 1 ? "s" : ""}`;
}
