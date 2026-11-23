import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LogoState, msUntilMidnight, SEASONAL_KEY } from "./logo.svelte";
import { LOGO_URLS, type LogoId } from "./logos";
import { SEASON_IDS } from "./season";

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

function make(
  storage: MemoryStorage | null = new MemoryStorage(),
  isDebug: () => Promise<boolean> = async () => false,
) {
  const applied: LogoId[] = [];
  const state = new LogoState({
    now: () => new Date(),
    storage: storage ? asStorage(storage) : null,
    applyIcon: async (id) => {
      applied.push(id);
    },
    isDebug,
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
  it("has a file for every season, and the debug hard hat", () => {
    expect(Object.keys(LOGO_URLS).sort()).toEqual(
      [...SEASON_IDS, "debug"].sort(),
    );
    for (const id of [...SEASON_IDS, "debug"] as const)
      expect(LOGO_URLS[id], id).toBeTruthy();
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
      isDebug: async () => false,
    });
    const stop = state.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(warn).toHaveBeenCalled();
    expect(state.id).toBe("christmas");
    stop();
  });
});

describe("LogoState in the debug build", () => {
  it("wears the hard hat whatever the setting and the date", async () => {
    for (const off of [false, true]) {
      const storage = new MemoryStorage();
      if (off) storage.setItem(SEASONAL_KEY, "0");
      const { state, applied } = make(storage, async () => true);
      const stop = state.start();
      await vi.advanceTimersByTimeAsync(0);
      expect(state.debug).toBe(true);
      expect(state.id).toBe("debug");
      expect(applied.at(-1)).toBe("debug");

      state.setSeasonal(!off);
      expect(state.id).toBe("debug");
      // Across midnights too: 25 Dec, then 3 days later.
      await vi.advanceTimersByTimeAsync(3 * 24 * 3600 * 1000);
      expect(state.id).toBe("debug");
      expect(applied.at(-1)).toBe("debug");
      stop();
    }
  });

  it("is not debug when the build cannot tell", async () => {
    const { state } = make(undefined, async () => {
      throw new Error("indisponible");
    });
    const stop = state.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(state.debug).toBe(false);
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
