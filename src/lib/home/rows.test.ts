import { describe, expect, it } from "vitest";
import type { Library } from "../emby/types";
import {
  DEFAULT_PREFS,
  defaultRows,
  isShown,
  moved,
  movedTo,
  orderedRows,
  prefsKey,
  readPrefs,
  savePrefs,
  toggled,
  visibleRows,
} from "./rows";

const lib = (id: string): Library => ({
  id,
  name: id.toUpperCase(),
  kind: "movies" as Library["kind"],
  image: null,
});

const keys = (rows: { key: string }[]) => rows.map((r) => r.key);

function memory(): Storage {
  const m = new Map<string, string>();
  return {
    get length() {
      return m.size;
    },
    clear: () => m.clear(),
    getItem: (k) => m.get(k) ?? null,
    key: () => null,
    removeItem: (k) => void m.delete(k),
    setItem: (k, v) => void m.set(k, v),
  };
}

describe("home rows", () => {
  const rows = defaultRows([lib("a"), lib("b")]);

  it("has the default order and labels", () => {
    expect(keys(rows)).toEqual([
      "resume",
      "nextUp",
      "favorites",
      "latest.a",
      "latest.b",
      "playlists",
    ]);
    expect(rows[3]?.label).toBe("Ajoutés récemment · A");
    expect(rows[5]?.label).toBe("Playlists");
  });

  it("follows the stored order, ignores unknown and duplicate keys", () => {
    expect(
      keys(orderedRows(rows, ["latest.b", "gone", "favorites", "latest.b"])),
      // resume has no default predecessor: first; nextUp follows it; latest.a follows favorites.
    ).toEqual([
      "resume",
      "nextUp",
      "latest.b",
      "playlists",
      "favorites",
      "latest.a",
    ]);
    expect(
      keys(
        orderedRows(rows, [
          "latest.b",
          "latest.a",
          "favorites",
          "nextUp",
          "resume",
          "gone",
        ]),
      ),
    ).toEqual([
      "latest.b",
      "playlists",
      "latest.a",
      "favorites",
      "nextUp",
      "resume",
    ]);
  });

  it("adds « Playlists » to a layout stored before it existed", () => {
    // Stored before the row existed: it comes right after the row that precedes
    // it by default, the last « Ajoutés récemment »; the default order is untouched.
    const stored = ["latest.b", "favorites", "resume", "nextUp", "latest.a"];
    expect(keys(orderedRows(rows, stored))).toEqual([
      "latest.b",
      "playlists",
      "favorites",
      "resume",
      "nextUp",
      "latest.a",
    ]);
    expect(keys(orderedRows(rows, []))).toEqual(keys(rows));
    const prefs = { order: ["latest.b", "latest.a"], hidden: ["playlists"] };
    expect(keys(visibleRows(rows, prefs))).not.toContain("playlists");
  });

  it("places a new library after its default predecessor", () => {
    const order = ["latest.a", "favorites", "nextUp", "resume"];
    const withC = defaultRows([lib("a"), lib("c"), lib("b")]);
    expect(keys(orderedRows(withC, order))).toEqual([
      "latest.a",
      "latest.c",
      "latest.b",
      "playlists",
      "favorites",
      "nextUp",
      "resume",
    ]);
  });

  it("skips hidden rows, a new one is shown", () => {
    const prefs = toggled(toggled(DEFAULT_PREFS, "resume"), "latest.a");
    expect(isShown(prefs, "resume")).toBe(false);
    expect(keys(visibleRows(rows, prefs))).toEqual([
      "nextUp",
      "favorites",
      "latest.b",
      "playlists",
    ]);
    expect(isShown(toggled(prefs, "resume"), "resume")).toBe(true);
  });

  it("moves up and down within bounds", () => {
    const down = moved(rows, DEFAULT_PREFS, "resume", 1);
    expect(down.order.slice(0, 2)).toEqual(["nextUp", "resume"]);
    expect(moved(rows, DEFAULT_PREFS, "resume", -1)).toBe(DEFAULT_PREFS);
    expect(moved(rows, DEFAULT_PREFS, "missing", 1)).toBe(DEFAULT_PREFS);
    expect(
      keys(orderedRows(rows, movedTo(rows, down, "latest.b", "nextUp").order)),
    ).toEqual([
      "latest.b",
      "nextUp",
      "resume",
      "favorites",
      "latest.a",
      "playlists",
    ]);
  });

  it("stores per server and user, survives bad data", () => {
    const s = memory();
    const key = prefsKey("srv", "u1");
    expect(key).not.toBe(prefsKey("srv", "u2"));
    expect(readPrefs(s, key)).toEqual(DEFAULT_PREFS);
    savePrefs(s, key, { order: ["favorites"], hidden: ["resume"] });
    expect(readPrefs(s, key)).toEqual({
      order: ["favorites"],
      hidden: ["resume"],
    });
    s.setItem(key, "{oops");
    expect(readPrefs(s, key)).toEqual(DEFAULT_PREFS);
    s.setItem(key, JSON.stringify({ order: [1, "x"], hidden: "no" }));
    expect(readPrefs(s, key)).toEqual({ order: ["x"], hidden: [] });
    expect(readPrefs(null, key)).toEqual(DEFAULT_PREFS);
  });
});
