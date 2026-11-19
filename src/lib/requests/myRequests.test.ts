import { describe, expect, it } from "vitest";
import pageFixture from "../../../contracts/request/fixtures/request-page.json";
import { openCount, SEGMENTS, segmentCount, totalCount } from "./myRequests";
import type { RequestPage, RequestTotals } from "./types";

const totals = (pageFixture as unknown as RequestPage).totals;
const by = (key: string) => {
  const s = SEGMENTS.find((x) => x.key === key);
  if (!s) throw new Error(key);
  return s;
};

describe("segments", () => {
  it("ask the server for the statuses of the spec", () => {
    expect(SEGMENTS.map((s) => [s.label, s.statuses])).toEqual([
      ["Toutes", null],
      ["En attente", ["pending"]],
      ["En cours", ["approved", "sent"]],
      ["Disponibles", ["available"]],
      ["Refusées", ["declined", "failed", "cancelled"]],
    ]);
  });

  it("count from the totals (fixture)", () => {
    expect(segmentCount(totals, by("all"))).toBe(7);
    expect(segmentCount(totals, by("pending"))).toBe(1);
    expect(segmentCount(totals, by("inProgress"))).toBe(1);
    expect(segmentCount(totals, by("available"))).toBe(4);
    expect(segmentCount(totals, by("declined"))).toBe(1);
  });

  it("every status falls in exactly one segment after « Toutes »", () => {
    const all: RequestTotals = {
      pending: 1,
      approved: 2,
      declined: 4,
      cancelled: 8,
      sent: 16,
      failed: 32,
      available: 64,
    };
    const sum = SEGMENTS.slice(1).reduce((n, s) => n + segmentCount(all, s), 0);
    expect(sum).toBe(totalCount(all));
    expect(totalCount(all)).toBe(127);
  });
});

describe("openCount", () => {
  it("adds pending, approved and sent", () => {
    expect(openCount(totals)).toBe(2);
    expect(
      openCount({
        pending: 3,
        approved: 2,
        sent: 1,
        declined: 9,
        cancelled: 9,
        failed: 9,
        available: 9,
      }),
    ).toBe(6);
  });
});
