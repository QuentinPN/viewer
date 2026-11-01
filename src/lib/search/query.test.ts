import { describe, expect, it } from "vitest";
import type { MediaItem, SearchResults, WatchItem } from "../emby/types";
import { cleanQuery, isSearchable, resultsLabel } from "./query";

const item = (id: string): MediaItem => ({
  id,
  name: `Titre ${id}`,
  itemType: "Movie",
  year: null,
  runtimeMinutes: null,
  poster: null,
  aspectRatio: null,
  played: false,
  unplayedCount: null,
});

const episode = (id: string): WatchItem => ({
  id,
  name: `Épisode ${id}`,
  kind: "episode",
  seriesName: "Série",
  seasonIndex: 1,
  index: 2,
  image: null,
  runtimeMinutes: null,
  resume: null,
});

const results = (
  movies: number,
  series: number,
  more = false,
  episodes = 0,
  moreEpisodes = false,
): SearchResults => ({
  query: "heures",
  movies: Array.from({ length: movies }, (_, i) => item(`m${i}`)),
  series: Array.from({ length: series }, (_, i) => item(`s${i}`)),
  episodes: Array.from({ length: episodes }, (_, i) => episode(`e${i}`)),
  moreMovies: more,
  moreSeries: false,
  moreEpisodes,
});

describe("cleanQuery", () => {
  it("trims and keeps single spaces between words", () => {
    expect(cleanQuery("\tLa  casa\r\nde papel ")).toBe("La casa de papel");
    expect(cleanQuery("   ")).toBe("");
  });

  it("drops control characters without splitting words", () => {
    expect(cleanQuery("a\u0000b\u001bc")).toBe("abc");
    expect(cleanQuery("  Heures\u0007  chrono\n")).toBe("Heures chrono");
  });

  it("keeps at most 100 characters, counted like Rust", () => {
    expect(Array.from(cleanQuery("é".repeat(150)))).toHaveLength(100);
    expect(Array.from(cleanQuery("🎬".repeat(120)))).toHaveLength(100);
    expect(cleanQuery(`${"a".repeat(99)} x`)).toBe("a".repeat(99));
  });
});

describe("isSearchable", () => {
  it("needs two characters", () => {
    expect(isSearchable("")).toBe(false);
    expect(isSearchable("a")).toBe(false);
    expect(isSearchable("🎬")).toBe(false);
    expect(isSearchable("ab")).toBe(true);
  });
});

describe("resultsLabel", () => {
  it("counts films and series", () => {
    expect(resultsLabel(results(2, 1))).toBe(
      "2 films et 1 série pour « heures »",
    );
    expect(resultsLabel(results(1, 0))).toBe("1 film pour « heures »");
    expect(resultsLabel(results(0, 3))).toBe("3 séries pour « heures »");
  });

  it("says when there are more than shown", () => {
    expect(resultsLabel(results(24, 0, true))).toBe(
      "plus de 24 films pour « heures »",
    );
  });

  it("counts episodes, at least that many when the server may hide some", () => {
    expect(resultsLabel(results(2, 1, false, 3))).toBe(
      "2 films, 1 série et 3 épisodes pour « heures »",
    );
    expect(resultsLabel(results(0, 0, false, 1))).toBe(
      "1 épisode pour « heures »",
    );
    expect(resultsLabel(results(0, 1, false, 1, true))).toBe(
      "1 série et au moins 1 épisode pour « heures »",
    );
  });

  it("says when nothing matched", () => {
    expect(resultsLabel(results(0, 0))).toBe("aucun résultat pour « heures »");
  });
});
