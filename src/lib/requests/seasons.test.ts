import { describe, expect, it } from "vitest";
import movieFixture from "../../../contracts/request/fixtures/media-movie.json";
import tvFixture from "../../../contracts/request/fixtures/media-tv.json";
import {
  canAsk,
  embyItem,
  isoDay,
  isRequestable,
  requestableSeasons,
  seasonsLabel,
} from "./seasons";
import type { Capabilities, MediaDetails } from "./types";

const TODAY = "2026-11-19";
const tv = tvFixture as unknown as MediaDetails;
const movie = movieFixture as unknown as MediaDetails;
const can = { canRequestMovies: true, canRequestTv: true } as Capabilities;

describe("requestable seasons", () => {
  it("keeps aired, open seasons after 0 (fixture)", () => {
    // 0 special, 1 available, 2 partial, 3 aired, 4 not aired yet, 5 pending.
    expect(requestableSeasons(tv, TODAY)).toEqual([2, 3]);
  });

  it("follows the air date day by day", () => {
    expect(requestableSeasons(tv, "2024-01-17")).toEqual([]);
    expect(requestableSeasons(tv, "2027-10-08")).toEqual([2, 3, 4]);
  });

  it("reopens a declined season, never one without episodes", () => {
    const series: MediaDetails = {
      ...tv,
      seasons: [
        {
          seasonNumber: 1,
          episodeCount: 8,
          airDate: "2020-01-01",
          status: "declined",
        },
        { seasonNumber: 2, episodeCount: 0, airDate: "2020-01-01" },
        {
          seasonNumber: 3,
          episodeCount: 8,
          airDate: "2020-01-01",
          status: "requested",
        },
      ],
    };
    expect(requestableSeasons(series, TODAY)).toEqual([1]);
  });
});

describe("requestable title", () => {
  it("asks for a film without status (fixture)", () => {
    expect(isRequestable(movie, TODAY)).toBe(true);
    expect(isRequestable({ ...movie, status: "declined" }, TODAY)).toBe(true);
    expect(isRequestable({ ...movie, status: "pending" }, TODAY)).toBe(false);
    expect(isRequestable({ ...movie, status: "available" }, TODAY)).toBe(false);
  });

  it("asks for a partial series with seasons left (fixture)", () => {
    expect(isRequestable(tv, TODAY)).toBe(true);
    expect(isRequestable({ ...tv, status: "available" }, TODAY)).toBe(false);
    const none = { ...tv, status: "requested" as const, seasons: [] };
    expect(isRequestable(none, TODAY)).toBe(false);
  });

  it("needs the right and no block", () => {
    expect(canAsk(movie, can, TODAY)).toBe(true);
    expect(canAsk(movie, { ...can, canRequestMovies: false }, TODAY)).toBe(
      false,
    );
    expect(canAsk(tv, { ...can, canRequestTv: false }, TODAY)).toBe(false);
    expect(canAsk({ ...movie, blocked: { by: "title" } }, can, TODAY)).toBe(
      false,
    );
    expect(canAsk(movie, null, TODAY)).toBe(false);
  });

  it("shows nothing to request for a series with no season aired yet", () => {
    const upcoming: MediaDetails = {
      ...tv,
      status: null,
      seasons: [{ seasonNumber: 1, episodeCount: 8, airDate: "2027-01-01" }],
    };
    expect(isRequestable(upcoming, TODAY)).toBe(true);
    expect(canAsk(upcoming, can, TODAY)).toBe(false);
  });
});

describe("labels and links", () => {
  it("counts the seasons of the button", () => {
    expect(seasonsLabel(1)).toBe("Demander 1 saison");
    expect(seasonsLabel(3)).toBe("Demander 3 saisons");
  });

  it("reads today in UTC", () => {
    expect(isoDay(new Date("2026-11-19T23:30:00-02:00"))).toBe("2026-11-20");
  });

  it("opens the Emby title only when it exists", () => {
    expect(embyItem(movie)).toBeNull();
    expect(embyItem(tv)).toMatchObject({
      id: tv.embyItemId,
      itemType: "Series",
      name: tv.title,
    });
  });
});
