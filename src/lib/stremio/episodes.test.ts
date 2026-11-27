import { describe, expect, it } from "vitest";
import type { Season } from "../requests/types";
import {
  defaultSeason,
  episodeNumbers,
  externalTarget,
  pickableSeasons,
  seasonLabel,
} from "./episodes";

function season(seasonNumber: number, episodeCount: number): Season {
  return { seasonNumber, episodeCount, airDate: null };
}

describe("pickableSeasons", () => {
  it("keeps the seasons with episodes, specials last", () => {
    const got = pickableSeasons([
      season(0, 4),
      season(3, 8),
      season(1, 10),
      season(2, 0),
    ]);
    expect(got.map((s) => s.seasonNumber)).toEqual([1, 3, 0]);
  });

  it("is empty when no season has an episode", () => {
    expect(pickableSeasons([season(1, 0), season(0, 0)])).toEqual([]);
  });
});

describe("seasonLabel", () => {
  it("names specials apart", () => {
    expect(seasonLabel(0)).toBe("Épisodes spéciaux");
    expect(seasonLabel(2)).toBe("Saison 2");
  });
});

describe("defaultSeason", () => {
  it("is the first regular season", () => {
    expect(defaultSeason(pickableSeasons([season(0, 2), season(2, 5)]))).toBe(
      2,
    );
  });

  it("falls back to the specials when they are the only ones", () => {
    expect(defaultSeason([season(0, 2)])).toBe(0);
  });

  it("is null without a season", () => {
    expect(defaultSeason([])).toBeNull();
  });
});

describe("episodeNumbers", () => {
  it("counts from 1", () => {
    expect(episodeNumbers(3)).toEqual([1, 2, 3]);
    expect(episodeNumbers(0)).toEqual([]);
  });
});

describe("externalTarget", () => {
  it("is a film without season or episode keys", () => {
    const t = externalTarget("tt0111161", "Les Évadés");
    expect(t).toEqual({
      external: { imdb: "tt0111161", name: "Les Évadés" },
    });
    expect(Object.keys(t.external)).toEqual(["imdb", "name"]);
  });

  it("is an episode with both numbers", () => {
    expect(
      externalTarget("tt0944947", "Game of Thrones", {
        season: 1,
        episode: 2,
      }),
    ).toEqual({
      external: {
        imdb: "tt0944947",
        name: "Game of Thrones",
        season: 1,
        episode: 2,
      },
    });
  });
});
