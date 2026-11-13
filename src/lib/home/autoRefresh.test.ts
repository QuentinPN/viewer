import { describe, expect, it } from "vitest";
import { HOME_STALE_MS, HOME_STALE_ON_RETURN_MS, isStale } from "./autoRefresh";

describe("isStale", () => {
  it("keeps fresh rows", () => {
    expect(isStale(1_000, 1_000 + HOME_STALE_MS - 1, HOME_STALE_MS)).toBe(
      false,
    );
  });

  it("reads rows again once the delay is reached", () => {
    expect(isStale(1_000, 1_000 + HOME_STALE_MS, HOME_STALE_MS)).toBe(true);
  });

  it("uses the shorter delay when coming back to the app", () => {
    const loadedAt = 0;
    const now = HOME_STALE_ON_RETURN_MS + 1;
    expect(isStale(loadedAt, now, HOME_STALE_ON_RETURN_MS)).toBe(true);
    expect(isStale(loadedAt, now, HOME_STALE_MS)).toBe(false);
  });
});
