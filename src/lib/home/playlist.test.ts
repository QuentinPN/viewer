import { describe, expect, it } from "vitest";
import type { WatchItem } from "../emby/types";
import { firstEntry, playlistSummary, titlesLabel } from "./playlist";

const entry = (id: string, runtimeMinutes: number | null): WatchItem => ({
  id,
  name: id,
  kind: "movie",
  seriesName: null,
  seasonIndex: null,
  index: null,
  image: null,
  runtimeMinutes,
  resume: null,
  played: false,
  favorite: false,
});

describe("playlist labels", () => {
  it("counts titles", () => {
    expect(titlesLabel(0)).toBe("0 titres");
    expect(titlesLabel(1)).toBe("1 titre");
    expect(titlesLabel(12)).toBe("12 titres");
  });

  it("adds the total length only when every entry has one", () => {
    expect(playlistSummary([entry("a", 120), entry("b", 132)])).toBe(
      "2 titres · 4 h 12",
    );
    expect(playlistSummary([entry("a", 120), entry("b", null)])).toBe(
      "2 titres",
    );
    expect(playlistSummary([entry("a", 0)])).toBe("1 titre");
    expect(playlistSummary([])).toBe("0 titres");
  });

  it("plays all from the first entry", () => {
    expect(firstEntry([entry("a", 1), entry("b", 1)])?.id).toBe("a");
    expect(firstEntry([])).toBeNull();
  });
});
