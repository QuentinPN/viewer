// The home reads its rows again on its own (ADR 0109): a title added on the
// server shows in « Ajoutés récemment » without restarting the app.

/** While the home stays shown, its rows are read again after this long. */
export const HOME_STALE_MS = 5 * 60_000;
/** Back to the app (focus or visibility): rows older than this are read again. */
export const HOME_STALE_ON_RETURN_MS = 30_000;
/** How often the open home checks its age. */
export const HOME_CHECK_MS = 60_000;

/** True when rows loaded at `loadedAt` are at least `after` ms old. */
export function isStale(loadedAt: number, now: number, after: number): boolean {
  return now - loadedAt >= after;
}
