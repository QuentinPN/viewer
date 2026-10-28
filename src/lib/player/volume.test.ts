import { describe, expect, it } from "vitest";
import {
  DEFAULT_VOLUME,
  effectiveVolume,
  readVolume,
  saveVolume,
  setLevel,
  stepVolume,
  toggleMute,
  VOLUME_KEY,
} from "./volume";

function memoryStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map(Object.entries(initial));
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (k) => data.get(k) ?? null,
    key: (i) => [...data.keys()][i] ?? null,
    removeItem: (k) => void data.delete(k),
    setItem: (k, v) => void data.set(k, v),
  };
}

describe("volume setting", () => {
  it("defaults to 100 without storage or saved value", () => {
    expect(readVolume(null)).toEqual(DEFAULT_VOLUME);
    expect(readVolume(memoryStorage())).toEqual(DEFAULT_VOLUME);
  });

  it("round-trips through storage", () => {
    const storage = memoryStorage();
    saveVolume(storage, { level: 35, muted: true });
    expect(readVolume(storage)).toEqual({ level: 35, muted: true });
  });

  it("ignores malformed values and clamps the level", () => {
    expect(readVolume(memoryStorage({ [VOLUME_KEY]: "{" }))).toEqual(
      DEFAULT_VOLUME,
    );
    expect(
      readVolume(memoryStorage({ [VOLUME_KEY]: '{"level":"fort"}' })),
    ).toEqual(DEFAULT_VOLUME);
    expect(
      readVolume(memoryStorage({ [VOLUME_KEY]: '{"level":180,"muted":1}' })),
    ).toEqual({ level: 100, muted: false });
  });

  it("survives a storage that refuses access", () => {
    const refusing = {
      getItem: () => {
        throw new Error("refusé");
      },
      setItem: () => {
        throw new Error("refusé");
      },
    } as unknown as Storage;
    expect(readVolume(refusing)).toEqual(DEFAULT_VOLUME);
    expect(() => saveVolume(refusing, DEFAULT_VOLUME)).not.toThrow();
  });

  it("mutes at 0 and keeps the level", () => {
    const muted = toggleMute({ level: 60, muted: false });
    expect(muted).toEqual({ level: 60, muted: true });
    expect(effectiveVolume(muted)).toBe(0);
    expect(toggleMute(muted)).toEqual({ level: 60, muted: false });
  });

  it("unmutes a level of 0 at a usable level", () => {
    expect(toggleMute({ level: 0, muted: true })).toEqual({
      level: 50,
      muted: false,
    });
  });

  it("steps within 0-100 and unmutes when raised", () => {
    expect(stepVolume({ level: 98, muted: false }, 5)).toEqual({
      level: 100,
      muted: false,
    });
    expect(stepVolume({ level: 3, muted: false }, -5)).toEqual({
      level: 0,
      muted: false,
    });
    expect(stepVolume({ level: 40, muted: true }, 5)).toEqual({
      level: 45,
      muted: false,
    });
    expect(stepVolume({ level: 40, muted: true }, -5)).toEqual({
      level: 35,
      muted: true,
    });
  });

  it("treats the slider at 0 as muted", () => {
    expect(setLevel(0)).toEqual({ level: 0, muted: true });
    expect(setLevel(42.4)).toEqual({ level: 42, muted: false });
  });
});
