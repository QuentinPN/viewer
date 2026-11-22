import { describe, expect, it } from "vitest";
import vectors from "../../../contracts/seasons/vectors.json";
import { SEASON_IDS, seasonFor, seasonForDate } from "./season";

// The same vectors are checked by the Android TV app
// (io.github.quentinpn.viewer.tv.brand.SeasonTest).
describe("seasonFor", () => {
  it("has vectors covering every season", () => {
    expect(new Set(vectors.map((v) => v.season))).toEqual(new Set(SEASON_IDS));
  });

  it.each(vectors)("$date is $season", ({ date, season }) => {
    const [y, m, d] = date.split("-").map(Number) as [number, number, number];
    expect(seasonFor(y, m, d)).toBe(season);
  });
});

describe("seasonForDate", () => {
  it("reads the local calendar date, not UTC", () => {
    // Dec 27 00:30 local is still Dec 26 in UTC for any zone east of UTC.
    expect(seasonForDate(new Date(2026, 11, 27, 0, 30))).toBe("new-year");
    expect(seasonForDate(new Date(2026, 11, 26, 23, 30))).toBe("christmas");
  });
});
