import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LogoState, msUntilMidnight, SEASONAL_KEY } from "./logo.svelte";
import { LOGO_URLS } from "./logos";
import { SEASON_IDS, type SeasonId } from "./season";

class MemoryStorage {
  data = new Map<string, string>();
  getItem(k: string) {
    return this.data.get(k) ?? null;
  }
  setItem(k: string, v: string) {
    this.data.set(k, v);
  }
}

const asStorage = (s: MemoryStorage) => s as unknown as Storage;

/** 25 December, 10:00 local: christmas. */
const CHRISTMAS = new Date(2026, 11, 25, 10, 0, 0);

function make(storage: MemoryStorage | null = new MemoryStorage()) {
  const applied: SeasonId[] = [];
  const state = new LogoState({
    now: () => new Date(),
    storage: storage ? asStorage(storage) : null,
    applyIcon: async (id) => {
      applied.push(id);
    },
  });
  return { state, applied, storage };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(CHRISTMAS);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("LOGO_URLS", () => {
  it("has a file for every season", () => {
    expect(Object.keys(LOGO_URLS).sort()).toEqual([...SEASON_IDS].sort());
    for (const id of SEASON_IDS) expect(LOGO_URLS[id], id).toBeTruthy();
  });
});

describe("LogoState", () => {
  it("shows the season of the day when the setting is on (the default)", () => {
    expect(make().state.id).toBe("christmas");
  });

  it("shows the default logo when the setting is off", () => {
    const storage = new MemoryStorage();
    storage.setItem(SEASONAL_KEY, "0");
    const { state } = make(storage);
    expect(state.seasonal).toBe(false);
    expect(state.id).toBe("default");
  });

  it("works without storage, with the setting on", () => {
    const { state } = make(null);
    expect(state.seasonal).toBe(true);
    state.setSeasonal(false);
    expect(state.id).toBe("default");
  });

  it("follows the setting, keeps it, and sets the window icon each time", async () => {
    const { state, applied, storage } = make();
    const stop = state.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(applied).toEqual(["christmas"]);

    state.setSeasonal(false);
    expect(state.id).toBe("default");
    expect(storage?.getItem(SEASONAL_KEY)).toBe("0");
    state.setSeasonal(true);
    expect(state.id).toBe("christmas");
    expect(storage?.getItem(SEASONAL_KEY)).toBe("1");
    await vi.advanceTimersByTimeAsync(0);
    expect(applied).toEqual(["christmas", "default", "christmas"]);
    stop();
  });

  it("does not set the window icon again when the logo does not change", async () => {
    const { state, applied } = make();
    const stop = state.start();
    state.refresh();
    state.setSeasonal(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(applied).toEqual(["christmas"]);
    stop();
  });

  it("re-evaluates at the next local midnight, each day", async () => {
    const { state, applied } = make();
    const stop = state.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(state.id).toBe("christmas");

    // 26 Dec 00:00 is still christmas; 27 Dec is new-year.
    await vi.advanceTimersByTimeAsync(msUntilMidnight(new Date()) + 1000);
    expect(new Date().getDate()).toBe(26);
    expect(state.id).toBe("christmas");
    expect(applied).toEqual(["christmas"]);

    await vi.advanceTimersByTimeAsync(24 * 3600 * 1000);
    expect(new Date().getDate()).toBe(27);
    expect(state.id).toBe("new-year");
    expect(applied).toEqual(["christmas", "new-year"]);
    stop();
  });

  it("stays on the default logo across midnights when the setting is off", async () => {
    const storage = new MemoryStorage();
    storage.setItem(SEASONAL_KEY, "0");
    const { state, applied } = make(storage);
    const stop = state.start();
    await vi.advanceTimersByTimeAsync(3 * 24 * 3600 * 1000);
    expect(state.id).toBe("default");
    expect(applied).toEqual(["default"]);
    stop();
  });

  it("clears its timer when stopped", async () => {
    const { state, applied } = make();
    const stop = state.start();
    expect(vi.getTimerCount()).toBe(1);
    stop();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(3 * 24 * 3600 * 1000);
    expect(applied.length).toBeLessThanOrEqual(1);
    expect(state.id).toBe("christmas");
  });

  it("starts again after a stop, with one timer", () => {
    const { state } = make();
    state.start()();
    const stop = state.start();
    expect(vi.getTimerCount()).toBe(1);
    stop();
  });

  it("keeps going when the window icon cannot be set", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const state = new LogoState({
      now: () => new Date(),
      storage: null,
      applyIcon: async () => {
        throw new Error("refusé");
      },
    });
    const stop = state.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(warn).toHaveBeenCalled();
    expect(state.id).toBe("christmas");
    stop();
  });
});

describe("msUntilMidnight", () => {
  it("is the time left in the local day", () => {
    expect(msUntilMidnight(new Date(2026, 4, 3, 23, 59, 0))).toBe(60_000);
    expect(msUntilMidnight(new Date(2026, 4, 3, 0, 0, 0))).toBe(24 * 3600_000);
  });
});
