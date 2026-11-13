import { describe, expect, it } from "vitest";
import { endLabel, endsAt, formatClock, untilNextMinute } from "./endtime";

const at = (h: number, m: number, s = 0) =>
  new Date(2026, 10, 13, h, m, s).getTime();

describe("formatClock", () => {
  it("pads hours and minutes, 24 h", () => {
    expect(formatClock(at(9, 5))).toBe("09:05");
    expect(formatClock(at(21, 47, 59))).toBe("21:47");
  });
});

describe("endsAt", () => {
  it("adds what is left of the duration", () => {
    expect(endsAt(at(21, 0), 600, 3600)).toBe(at(21, 50));
  });
  it("is shorter at a higher speed", () => {
    expect(endsAt(at(21, 0), 0, 3600, 2)).toBe(at(21, 30));
  });
  it("is null without a duration", () => {
    expect(endsAt(at(21, 0), 10, 0)).toBeNull();
    expect(endsAt(at(21, 0), 10, Number.NaN)).toBeNull();
  });
  it("never goes before now", () => {
    expect(endsAt(at(21, 0), 4000, 3600)).toBe(at(21, 0));
  });
  it("treats a bad speed as normal", () => {
    expect(endsAt(at(21, 0), 0, 60, 0)).toBe(at(21, 1));
  });
});

describe("endLabel", () => {
  it("reads « Fin à »", () => {
    expect(endLabel(at(21, 0), 0, 6420)).toBe("Fin à 22:47");
    expect(endLabel(at(21, 0), 0, 0)).toBeNull();
  });
});

describe("untilNextMinute", () => {
  it("waits for the next minute, never zero", () => {
    expect(untilNextMinute(at(21, 0, 30))).toBe(30_000);
    expect(untilNextMinute(at(21, 0))).toBe(60_000);
  });
});
