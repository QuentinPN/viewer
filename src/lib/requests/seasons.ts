// What a member may request, copied from Request
// (`src/shared/requests.ts:37-49`, `status.ts:97` at main@54cd791).
import type { MediaItem } from "../emby/types";
import type {
  Capabilities,
  MediaDetails,
  MediaStatus,
  MediaSummary,
  Season,
} from "./types";

/** Today in UTC as `AAAA-MM-JJ`, the server's comparison of air dates. */
export function isoDay(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}

/** No status, partial or declined: may be requested (again). */
export function openStatus(status: MediaStatus | null | undefined): boolean {
  return !status || status === "partial" || status === "declined";
}

/** Season after 0, aired (episodes and an air date up to today) and open. */
export function isSeasonRequestable(s: Season, today: string): boolean {
  return (
    s.seasonNumber > 0 &&
    s.episodeCount > 0 &&
    s.airDate !== null &&
    s.airDate <= today &&
    openStatus(s.status)
  );
}

export function requestableSeasons(
  details: MediaDetails,
  today: string = isoDay(),
): number[] {
  return details.seasons
    .filter((s) => isSeasonRequestable(s, today))
    .map((s) => s.seasonNumber);
}

/**
 * Film: open status. Series: not available, and an open status or at least one
 * requestable season.
 */
export function isRequestable(
  details: MediaDetails,
  today: string = isoDay(),
): boolean {
  if (details.type === "movie") return openStatus(details.status);
  if (details.status === "available") return false;
  return (
    openStatus(details.status) || requestableSeasons(details, today).length > 0
  );
}

/**
 * « Demander » shows: the right is granted, nothing blocks, something is left. A
 * series also needs a season to tick: an open title with none aired yet would
 * give an empty dialog, so it reads « Rien à demander ».
 */
export function canAsk(
  details: MediaDetails,
  capabilities: Capabilities | null,
  today: string = isoDay(),
): boolean {
  if (!capabilities || details.blocked) return false;
  const allowed =
    details.type === "movie"
      ? capabilities.canRequestMovies
      : capabilities.canRequestTv;
  if (!allowed || !isRequestable(details, today)) return false;
  return (
    details.type === "movie" || requestableSeasons(details, today).length > 0
  );
}

export function seasonsLabel(n: number): string {
  return n > 1 ? `Demander ${n} saisons` : `Demander ${n} saison`;
}

/** The Emby title behind « Lire », opened by its id in the existing detail page. */
export function embyItem(
  details: MediaSummary & { runtime?: number | null },
): MediaItem | null {
  if (!details.embyItemId) return null;
  return {
    id: details.embyItemId,
    name: details.title,
    itemType: details.type === "movie" ? "Movie" : "Series",
    year: details.year,
    runtimeMinutes: details.runtime ?? null,
    poster: null,
    aspectRatio: null,
    played: false,
    unplayedCount: null,
    favorite: false,
  };
}
