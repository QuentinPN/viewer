// Pure helpers of Découvrir and Parcourir: filters, pages, cards.
import type { MediaItem } from "../emby/types";
import type {
  BrowseFilters,
  DiscoverResponse,
  DiscoverRow,
  MediaSummary,
  RequestView,
  SeeAll,
  SeeAllFilters,
  SortOrder,
} from "./types";

export const SORTS: { key: SortOrder; label: string }[] = [
  { key: "popularity", label: "Popularité" },
  { key: "rating", label: "Note" },
  { key: "release", label: "Date de sortie" },
  { key: "title", label: "Titre" },
];

export const RATINGS = [5, 6, 7, 8];

export const DEFAULT_FILTERS: SeeAllFilters = { sort: "popularity" };

/** The filters without their empty fields: what is sent, compared and shown. */
export function cleanFilters(f: SeeAllFilters): SeeAllFilters {
  const out: SeeAllFilters = {};
  for (const [key, value] of Object.entries(f) as [
    keyof SeeAllFilters,
    unknown,
  ][]) {
    if (value === null || value === undefined || value === "") continue;
    if (Array.isArray(value) && value.length === 0) continue;
    (out as Record<string, unknown>)[key] = value;
  }
  return out;
}

/** « Tout voir » of a Découvrir row: Parcourir with every filter of the row. */
export function filtersFromSeeAll(seeAll: SeeAll): SeeAllFilters {
  return { ...DEFAULT_FILTERS, ...cleanFilters(seeAll.filters) };
}

/** Argument of `ur_browse` for one page. */
export function browseArgs(f: SeeAllFilters, page: number): BrowseFilters {
  return { ...cleanFilters(f), page: Math.max(1, page) };
}

export function sameFilters(a: SeeAllFilters, b: SeeAllFilters): boolean {
  const x = cleanFilters(a);
  const y = cleanFilters(b);
  const keys = new Set([...Object.keys(x), ...Object.keys(y)]);
  return [...keys].every(
    (k) =>
      JSON.stringify(x[k as keyof SeeAllFilters]) ===
      JSON.stringify(y[k as keyof SeeAllFilters]),
  );
}

/** Something differs from the default: « Réinitialiser » shows. */
export function canReset(f: SeeAllFilters): boolean {
  return !sameFilters(f, DEFAULT_FILTERS);
}

/** One genre chosen in the select, or none; several (from a row) read as « mixed ». */
export function genreChoice(f: SeeAllFilters): number | "mixed" | null {
  const g = f.genres ?? [];
  if (g.length === 0) return null;
  return g.length === 1 ? (g[0] ?? null) : "mixed";
}

export function withGenre(
  f: SeeAllFilters,
  genre: number | null,
): SeeAllFilters {
  return { ...f, genres: genre === null ? null : [genre] };
}

/** The decade the years cover exactly, else null. */
export function decadeOf(f: SeeAllFilters): number | null {
  const from = f.yearFrom ?? null;
  const to = f.yearTo ?? null;
  if (from === null || to === null) return null;
  return from % 10 === 0 && to === from + 9 ? from : null;
}

export function withDecade(
  f: SeeAllFilters,
  decade: number | null,
): SeeAllFilters {
  return decade === null
    ? { ...f, yearFrom: null, yearTo: null }
    : { ...f, yearFrom: decade, yearTo: decade + 9 };
}

/** Decades offered, newest first, from the current one back to 1920. */
export function decades(year: number): number[] {
  const out: number[] = [];
  for (let d = Math.floor(year / 10) * 10; d >= 1920; d -= 10) out.push(d);
  return out;
}

/** Years that are not a decade (from a row): « Depuis 2020 », « 1990 – 1995 ». */
export function yearsLabel(f: SeeAllFilters): string | null {
  const from = f.yearFrom ?? null;
  const to = f.yearTo ?? null;
  if (from === null && to === null) return null;
  const decade = decadeOf(f);
  if (decade !== null) return `Années ${decade}`;
  if (to === null) return `Depuis ${from}`;
  if (from === null) return `Jusqu'à ${to}`;
  return from === to ? String(from) : `${from} – ${to}`;
}

/** Results of the next page after the loaded ones, without a title twice. */
export function mergePage(
  loaded: MediaSummary[],
  next: MediaSummary[],
): MediaSummary[] {
  const seen = new Set(loaded.map(mediaKey));
  const added: MediaSummary[] = [];
  for (const m of next) {
    const key = mediaKey(m);
    if (seen.has(key)) continue;
    seen.add(key);
    added.push(m);
  }
  return added.length === 0 ? loaded : [...loaded, ...added];
}

export function mediaKey(m: { type: string; tmdbId: number }): string {
  return `${m.type}:${m.tmdbId}`;
}

/** Rows in the server's `layout` order, then the others; empty rows dropped. */
export function orderedRows(d: DiscoverResponse): DiscoverRow[] {
  const rank = (r: DiscoverRow) => {
    const i = d.layout.indexOf(r.id);
    return i < 0 ? d.layout.length : i;
  };
  return d.rows
    .filter((r) => r.items.length > 0)
    .map((r, i) => ({ r, i }))
    .sort((a, b) => rank(a.r) - rank(b.r) || a.i - b.i)
    .map(({ r }) => r);
}

/**
 * A title as a library poster card: no Emby menu (unknown item type), the
 * artwork from the `requestimg` protocol.
 */
export function cardItem(m: MediaSummary): MediaItem {
  return {
    id: `ur-${mediaKey(m)}`,
    name: m.title,
    itemType: m.type === "movie" ? "RequestMovie" : "RequestSeries",
    year: m.year,
    runtimeMinutes: null,
    poster: m.poster,
    aspectRatio: null,
    played: false,
    unplayedCount: null,
    favorite: false,
  };
}

const IN_FLIGHT = new Set(["pending", "approved", "sent"]);
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * « Mes demandes en cours »: waiting, approved or sent, plus those that became
 * available in the last 7 days; at most `max`.
 */
export function inFlight(
  requests: RequestView[],
  now: number,
  max = 3,
): RequestView[] {
  return requests
    .filter(
      (r) =>
        IN_FLIGHT.has(r.status) ||
        (r.status === "available" && now - Date.parse(r.updatedAt) <= WEEK_MS),
    )
    .slice(0, max);
}
