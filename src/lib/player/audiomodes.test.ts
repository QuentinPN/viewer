import { describe, expect, it } from "vitest";
import {
  formatSpeed,
  offeredSpeed,
  readNightMode,
  saveNightMode,
  speedBadge,
} from "./audiomodes";

function memory(): Storage {
  const map = new Map<string, string>();
  return {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k),
    clear: () => map.clear(),
    key: () => null,
    get length() {
      return map.size;
    },
  };
}

describe("speed", () => {
  it("snaps to an offered speed", () => {
    expect(offeredSpeed(1.4)).toBe(1.5);
    expect(offeredSpeed(3)).toBe(2);
    expect(offeredSpeed(Number.NaN)).toBe(1);
    expect(offeredSpeed(-1)).toBe(1);
  });
  it("labels in French", () => {
    expect(formatSpeed(1)).toBe("Normale");
    expect(formatSpeed(0.75)).toBe("0,75×");
    expect(formatSpeed(1.5)).toBe("1,5×");
    expect(formatSpeed(2)).toBe("2×");
  });
  it("shows a badge only off normal", () => {
    expect(speedBadge(1)).toBeNull();
    expect(speedBadge(1.25)).toBe("1,25×");
  });
});

describe("night mode", () => {
  it("is off by default and kept once set", () => {
    const s = memory();
    expect(readNightMode(s)).toBe(false);
    saveNightMode(s, true);
    expect(readNightMode(s)).toBe(true);
    expect(readNightMode(null)).toBe(false);
  });
});
