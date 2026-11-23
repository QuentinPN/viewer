// « Regarder maintenant » on a Demander series: the seasons and episodes that
// can be picked, and the outside title the sources are asked for.
import type { Season } from "../requests/types";
import type { ExternalTarget } from "./types";

/** Seasons with episodes, in order; the specials (season 0) come last. */
export function pickableSeasons(seasons: readonly Season[]): Season[] {
  const order = (n: number) => (n === 0 ? Number.POSITIVE_INFINITY : n);
  return seasons
    .filter((s) => s.episodeCount > 0)
    .sort((a, b) => order(a.seasonNumber) - order(b.seasonNumber));
}

export function seasonLabel(seasonNumber: number): string {
  return seasonNumber === 0 ? "Épisodes spéciaux" : `Saison ${seasonNumber}`;
}

/** The first regular season of `pickableSeasons`, else the specials. */
export function defaultSeason(seasons: readonly Season[]): number | null {
  const regular = seasons.find((s) => s.seasonNumber > 0);
  return (regular ?? seasons[0])?.seasonNumber ?? null;
}

/** 1 … count. */
export function episodeNumbers(count: number): number[] {
  return Array.from({ length: Math.max(0, count) }, (_, i) => i + 1);
}

/**
 * The `stremio_sources` argument for an outside title. A film has no season
 * or episode key at all: Go refuses a half or empty pair.
 */
export function externalTarget(
  imdb: string,
  name: string,
  pick?: { season: number; episode: number },
): { external: ExternalTarget } {
  return {
    external: pick
      ? { imdb, name, season: pick.season, episode: pick.episode }
      : { imdb, name },
  };
}
