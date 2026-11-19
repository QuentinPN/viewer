import { describe, expect, it } from "vitest";
import {
  browseArgs,
  canReset,
  cardItem,
  DEFAULT_FILTERS,
  decadeOf,
  decades,
  filtersFromSeeAll,
  genreChoice,
  inFlight,
  mergePage,
  orderedRows,
  withDecade,
  withGenre,
  yearsLabel,
} from "./browse";
import type { DiscoverRow, MediaSummary, RequestView } from "./types";

function media(tmdbId: number, type: "movie" | "tv" = "movie"): MediaSummary {
  return {
    tmdbId,
    type,
    title: `Titre ${tmdbId}`,
    year: 2024,
    posterPath: null,
    backdropPath: null,
    overview: "",
    poster: null,
    backdrop: null,
  };
}

describe("filters", () => {
  it("opens « Tout voir » with every filter of the row", () => {
    expect(
      filtersFromSeeAll({
        type: "movie",
        filters: { sort: "rating", yearFrom: 2020, rating: 7, genres: [] },
      }),
    ).toEqual({ sort: "rating", yearFrom: 2020, rating: 7 });
    expect(
      filtersFromSeeAll({ type: "tv", filters: { genres: [18, 80] } }),
    ).toEqual({ sort: "popularity", genres: [18, 80] });
  });

  it("sends a page and drops empty fields", () => {
    expect(
      browseArgs({ sort: "title", genres: [], yearFrom: null, rating: 6 }, 0),
    ).toEqual({ sort: "title", rating: 6, page: 1 });
    expect(browseArgs(DEFAULT_FILTERS, 3)).toEqual({
      sort: "popularity",
      page: 3,
    });
  });

  it("resets only when something differs from the default", () => {
    expect(canReset(DEFAULT_FILTERS)).toBe(false);
    expect(canReset({ sort: "popularity", genres: [] })).toBe(false);
    expect(canReset({ sort: "title" })).toBe(true);
    expect(canReset({ ...DEFAULT_FILTERS, rating: 7 })).toBe(true);
  });

  it("maps the genre select", () => {
    expect(genreChoice({})).toBeNull();
    expect(genreChoice({ genres: [18] })).toBe(18);
    expect(genreChoice({ genres: [18, 80] })).toBe("mixed");
    expect(withGenre({ genres: [18, 80] }, 35).genres).toEqual([35]);
    expect(withGenre({ genres: [18] }, null).genres).toBeNull();
  });

  it("maps the years select to decades", () => {
    const f = withDecade(DEFAULT_FILTERS, 1990);
    expect(f).toMatchObject({ yearFrom: 1990, yearTo: 1999 });
    expect(decadeOf(f)).toBe(1990);
    expect(decadeOf({ yearFrom: 2020 })).toBeNull();
    expect(withDecade(f, null)).toMatchObject({ yearFrom: null, yearTo: null });
    expect(decades(2026).slice(0, 3)).toEqual([2020, 2010, 2000]);
    expect(decades(2026).at(-1)).toBe(1920);
  });

  it("names years that are not a decade", () => {
    expect(yearsLabel({})).toBeNull();
    expect(yearsLabel({ yearFrom: 2020 })).toBe("Depuis 2020");
    expect(yearsLabel({ yearTo: 1980 })).toBe("Jusqu'à 1980");
    expect(yearsLabel({ yearFrom: 1990, yearTo: 1995 })).toBe("1990 – 1995");
    expect(yearsLabel({ yearFrom: 1990, yearTo: 1999 })).toBe("Années 1990");
  });
});

describe("pages", () => {
  it("adds the next page without a title twice", () => {
    const loaded = [media(1), media(2)];
    const merged = mergePage(loaded, [
      media(2),
      media(3),
      media(3),
      media(2, "tv"),
    ]);
    expect(merged.map((m) => `${m.type}:${m.tmdbId}`)).toEqual([
      "movie:1",
      "movie:2",
      "movie:3",
      "tv:2",
    ]);
  });

  it("keeps the same list when nothing is new", () => {
    const loaded = [media(1)];
    expect(mergePage(loaded, [media(1)])).toBe(loaded);
  });
});

describe("Découvrir", () => {
  const row = (id: string, n: number): DiscoverRow => ({
    id,
    title: id,
    items: Array.from({ length: n }, (_, i) => media(i + 1)),
  });

  it("follows the layout and drops empty rows", () => {
    const rows = orderedRows({
      rows: [row("b", 2), row("extra", 1), row("a", 1), row("empty", 0)],
      layout: ["a", "b", "empty"],
    });
    expect(rows.map((r) => r.id)).toEqual(["a", "b", "extra"]);
  });

  it("makes a library card without an Emby menu", () => {
    const item = cardItem({ ...media(7, "tv"), year: 2021 });
    expect(item.id).toBe("ur-tv:7");
    expect(item.itemType).toBe("RequestSeries");
    expect(item.year).toBe(2021);
  });
});

describe("Mes demandes en cours", () => {
  const now = Date.parse("2026-11-19T12:00:00Z");
  const request = (
    id: number,
    status: RequestView["status"],
    updatedAt = "2026-11-18T10:00:00Z",
  ) => ({ id, status, updatedAt }) as RequestView;

  it("keeps waiting requests and those available this week, three at most", () => {
    const list = [
      request(1, "declined"),
      request(2, "pending"),
      request(3, "available", "2026-11-01T00:00:00Z"),
      request(4, "available"),
      request(5, "sent"),
      request(6, "approved"),
    ];
    expect(inFlight(list, now).map((r) => r.id)).toEqual([2, 4, 5]);
    expect(inFlight(list, now, 10).map((r) => r.id)).toEqual([2, 4, 5, 6]);
  });
});
