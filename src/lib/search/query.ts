import type { SearchResults } from "../emby/types";

// Search field helpers. Rust cleans the query the same way before it
// reaches the server; the UI cleans it too to know when to search at all.

/** Emby returns nothing for a single letter. */
export const SEARCH_MIN_CHARS = 2;
export const SEARCH_MAX_CHARS = 100;
/** Delay after the last key before searching; Enter searches at once. */
export const SEARCH_DEBOUNCE_MS = 250;
/** Per type, as `SearchShown` in go-app/internal/emby/session.go. */
export const SEARCH_SHOWN = 24;

/**
 * Control characters dropped, single spaces between words, at most 100
 * characters (code points, like Rust's `chars()`).
 */
export function cleanQuery(raw: string): string {
  const words = raw
    // biome-ignore lint/suspicious/noControlCharactersInRegex: dropping them is the point
    .replace(/[\u0000-\u0008\u000e-\u001f\u007f-\u009f]/g, "")
    .split(/\s+/)
    .filter((w) => w !== "");
  return Array.from(words.join(" "))
    .slice(0, SEARCH_MAX_CHARS)
    .join("")
    .trimEnd();
}

/** Long enough to be sent to the server. */
export function isSearchable(cleaned: string): boolean {
  return Array.from(cleaned).length >= SEARCH_MIN_CHARS;
}

function count(
  n: number,
  more: boolean,
  one: string,
  many: string,
  maybe = false,
): string {
  const prefix = more ? (maybe ? "au moins " : "plus de ") : "";
  return `${prefix}${n} ${n > 1 || (more && !maybe) ? many : one}`;
}

/** What the search found, also announced to screen readers. */
export function resultsLabel(results: SearchResults): string {
  const parts = [];
  if (results.movies.length > 0) {
    parts.push(
      count(results.movies.length, results.moreMovies, "film", "films"),
    );
  }
  if (results.series.length > 0) {
    parts.push(
      count(results.series.length, results.moreSeries, "série", "séries"),
    );
  }
  if (results.episodes.length > 0) {
    parts.push(
      count(
        results.episodes.length,
        results.moreEpisodes,
        "épisode",
        "épisodes",
        true,
      ),
    );
  }
  if (parts.length === 0) return `aucun résultat pour « ${results.query} »`;
  const last = parts.pop();
  const head = parts.join(", ");
  return `${head ? `${head} et ` : ""}${last} pour « ${results.query} »`;
}
