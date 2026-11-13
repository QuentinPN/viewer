import { cleanQuery, isSearchable } from "./query";

// Recent searches (ADR 0122), as on the TV (ADR 0075): kept on this PC only,
// per Emby server and user, never sent to the server. Search terms are not a
// secret; the token is never kept here.

/** Searches kept, newest first. */
export const SEARCH_HISTORY_MAX = 8;

export const historyKey = (serverId: string, userId: string) =>
  `viewer.search-history.v1:${serverId}:${userId}`;

/** Case and accents aside, as Emby matches titles. */
function fold(text: string): string {
  return text.toLocaleLowerCase("fr").normalize("NFD").replace(/\p{M}/gu, "");
}

/**
 * `history` with `raw` first: cleaned as sent to the server, an older spelling
 * of the same term dropped, at most SEARCH_HISTORY_MAX. A term too short to be
 * searched leaves the history as it was.
 */
export function addSearch(history: string[], raw: string): string[] {
  const query = cleanQuery(raw);
  if (!isSearchable(query)) return history;
  const key = fold(query);
  return [query, ...history.filter((h) => fold(h) !== key)].slice(
    0,
    SEARCH_HISTORY_MAX,
  );
}

/** Storage may be missing, throw or hold garbage: stored terms are cleaned again. */
export function readHistory(storage: Storage | null, key: string): string[] {
  try {
    const raw = storage?.getItem(key);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((s): s is string => typeof s === "string")
      .reverse()
      .reduce<string[]>(addSearch, []);
  } catch {
    return [];
  }
}

export function saveHistory(
  storage: Storage | null,
  key: string,
  history: string[],
): void {
  try {
    if (history.length === 0) storage?.removeItem(key);
    else storage?.setItem(key, JSON.stringify(history));
  } catch {
    // Quota or refused storage: the history lasts until the app closes.
  }
}
