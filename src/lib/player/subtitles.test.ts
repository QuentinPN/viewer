import { describe, expect, it } from "vitest";
import {
  DEFAULT_PREFERENCES,
  type PlaybackPreferences,
  readPreferences,
  SUBTITLES_KEY,
  savePreferences,
} from "./subtitles";

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

describe("subtitle preferences", () => {
  it("defaults to the server's picks and mpv's look", () => {
    expect(readPreferences(null)).toEqual(DEFAULT_PREFERENCES);
    expect(readPreferences(memoryStorage())).toEqual(DEFAULT_PREFERENCES);
    expect(DEFAULT_PREFERENCES.tracks.subtitleMode).toBe("server");
  });

  it("round-trips a saved choice", () => {
    const storage = memoryStorage();
    const chosen: PlaybackPreferences = {
      tracks: {
        audioLanguage: "eng",
        subtitleLanguage: "fre",
        subtitleMode: "always",
      },
      subtitleStyle: {
        size: "larger",
        color: "yellow",
        background: "box",
        position: "raised",
        overrideAss: true,
      },
    };
    savePreferences(storage, chosen);
    expect(readPreferences(storage)).toEqual(chosen);
  });

  it("drops unknown or malformed values field by field", () => {
    const storage = memoryStorage({
      [SUBTITLES_KEY]: JSON.stringify({
        tracks: {
          audioLanguage: "fra",
          subtitleLanguage: "eng",
          subtitleMode: "smart",
        },
        subtitleStyle: { size: "huge", color: "yellow", overrideAss: "yes" },
      }),
    });
    const read = readPreferences(storage);
    // `fra` is a spelling the native side matches, not a choice it accepts.
    expect(read.tracks).toEqual({
      audioLanguage: null,
      subtitleLanguage: "eng",
      subtitleMode: "server",
    });
    expect(read.subtitleStyle).toEqual({
      ...DEFAULT_PREFERENCES.subtitleStyle,
      color: "yellow",
    });
    expect(readPreferences(memoryStorage({ [SUBTITLES_KEY]: "{" }))).toEqual(
      DEFAULT_PREFERENCES,
    );
    expect(readPreferences(memoryStorage({ [SUBTITLES_KEY]: "null" }))).toEqual(
      DEFAULT_PREFERENCES,
    );
  });

  it("returns a copy the caller may change", () => {
    const read = readPreferences(null);
    read.tracks.audioLanguage = "eng";
    expect(DEFAULT_PREFERENCES.tracks.audioLanguage).toBeNull();
  });

  it("survives a storage that refuses writes", () => {
    const refusing = memoryStorage();
    refusing.setItem = () => {
      throw new Error("quota");
    };
    expect(() => savePreferences(refusing, DEFAULT_PREFERENCES)).not.toThrow();
  });
});
