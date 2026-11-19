// Request search next to the Emby one (Recherche C, ADR 0125).
import type { MediaSummary } from "./types";

/**
 * « À demander »: what Emby does not have (no `embyItemId`, which the server
 * only sets for an available or partial title) and nothing blocks.
 */
export function toRequest(results: MediaSummary[]): MediaSummary[] {
  return results.filter((m) => !m.embyItemId && !m.blocked);
}

/** Latest call wins: an answer to an older query is dropped. */
export function latestOnly() {
  let current = 0;
  return {
    next: () => ++current,
    isCurrent: (ticket: number) => ticket === current,
  };
}
