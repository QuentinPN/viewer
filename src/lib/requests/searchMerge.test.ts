import { describe, expect, it } from "vitest";
import searchFixture from "../../../contracts/request/fixtures/search.json";
import { latestOnly, toRequest } from "./searchMerge";
import type { MediaSearchResults, MediaSummary } from "./types";

const search = searchFixture as unknown as MediaSearchResults;

describe("toRequest", () => {
  it("keeps the titles Emby does not have (fixture)", () => {
    const kept = toRequest(search.results);
    expect(kept.length).toBe(4);
    expect(kept.every((m) => !m.embyItemId)).toBe(true);
    expect(kept.map((m) => m.title)).not.toContain("Dune : Deuxième partie");
  });

  it("drops blocked titles", () => {
    const base = search.results[1] as MediaSummary;
    const blocked = { ...base, tmdbId: 1, blocked: { by: "title" as const } };
    const keyword = {
      ...base,
      tmdbId: 2,
      blocked: { by: "keyword" as const, keyword: "gore" },
    };
    expect(toRequest([base, blocked, keyword])).toEqual([base]);
  });
});

describe("latestOnly", () => {
  it("only the latest ticket is current", () => {
    const calls = latestOnly();
    const first = calls.next();
    const second = calls.next();
    expect(calls.isCurrent(first)).toBe(false);
    expect(calls.isCurrent(second)).toBe(true);
  });
});
