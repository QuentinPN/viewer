import { describe, expect, it } from "vitest";
import {
  DEFAULT_OPTIONS,
  directionLabels,
  isDefault,
  isFiltered,
  loadOptions,
  noMatchLabel,
  SORTS,
  sameOptions,
  sanitize,
  saveOptions,
  storageKey,
  watchChoices,
  withSort,
} from "./options";

class MemoryStorage {
  map = new Map<string, string>();
  getItem(k: string) {
    return this.map.get(k) ?? null;
  }
  setItem(k: string, v: string) {
    this.map.set(k, v);
  }
  removeItem(k: string) {
    this.map.delete(k);
  }
}

describe("library options", () => {
  it("defaults to title A→Z with nothing filtered", () => {
    expect(isDefault(DEFAULT_OPTIONS)).toBe(true);
    expect(isFiltered(DEFAULT_OPTIONS)).toBe(false);
    expect(directionLabels("name").ascending).toBe("A → Z");
  });

  it("gives each sort its natural direction", () => {
    expect(withSort(DEFAULT_OPTIONS, "dateAdded")).toMatchObject({
      sort: "dateAdded",
      descending: true,
    });
    expect(withSort(DEFAULT_OPTIONS, "rating").descending).toBe(true);
    const back = withSort(withSort(DEFAULT_OPTIONS, "year"), "name");
    expect(isDefault(back)).toBe(true);
    for (const { key } of SORTS) expect(directionLabels(key)).toBeTruthy();
  });

  it("tells a sort from a filter", () => {
    const sorted = withSort(DEFAULT_OPTIONS, "year");
    expect(isDefault(sorted)).toBe(false);
    expect(isFiltered(sorted)).toBe(false);
    expect(isFiltered({ ...DEFAULT_OPTIONS, favorites: true })).toBe(true);
    expect(isFiltered({ ...DEFAULT_OPTIONS, genre: "Action" })).toBe(true);
    expect(isFiltered({ ...DEFAULT_OPTIONS, watch: "played" })).toBe(true);
    expect(sameOptions(sorted, { ...sorted })).toBe(true);
    expect(sameOptions(sorted, DEFAULT_OPTIONS)).toBe(false);
  });

  it("offers « En cours » for films only", () => {
    expect(watchChoices("movies").map((c) => c.key)).toContain("inProgress");
    expect(watchChoices("tvShows").map((c) => c.key)).not.toContain(
      "inProgress",
    );
    expect(sanitize({ watch: "inProgress" }, "tvShows").watch).toBe("all");
    expect(noMatchLabel("tvShows")).toBe(
      "Aucune série ne correspond à ces filtres",
    );
  });

  it("repairs whatever storage gives back", () => {
    expect(sanitize(null, "movies")).toEqual(DEFAULT_OPTIONS);
    expect(sanitize("x", "movies")).toEqual(DEFAULT_OPTIONS);
    expect(
      sanitize(
        { sort: "random", watch: 3, favorites: "yes", genre: "A|B" },
        "movies",
      ),
    ).toEqual(DEFAULT_OPTIONS);
    // A known sort without a direction gets its natural one.
    expect(sanitize({ sort: "dateAdded" }, "movies").descending).toBe(true);
    for (const genre of ["", "  ", "a\u0007b", "x".repeat(101), 42]) {
      expect(sanitize({ genre }, "movies").genre).toBeNull();
    }
    expect(
      sanitize(
        {
          sort: "year",
          descending: false,
          watch: "unplayed",
          favorites: true,
          genre: "Science-Fiction & Fantastique",
        },
        "tvShows",
      ),
    ).toEqual({
      sort: "year",
      descending: false,
      watch: "unplayed",
      favorites: true,
      genre: "Science-Fiction & Fantastique",
    });
  });

  it("remembers choices per server, user and library", () => {
    const storage = new MemoryStorage();
    const a = storageKey("srv", "u1", "lib1");
    const b = storageKey("srv", "u2", "lib1");
    expect(a).not.toBe(b);
    const chosen = { ...withSort(DEFAULT_OPTIONS, "rating"), favorites: true };
    saveOptions(storage, a, chosen);
    expect(loadOptions(storage, a, "movies")).toEqual(chosen);
    expect(loadOptions(storage, b, "movies")).toEqual(DEFAULT_OPTIONS);
    saveOptions(storage, a, DEFAULT_OPTIONS);
    expect(storage.map.size).toBe(0);
  });

  it("falls back to defaults when storage is unusable", () => {
    const broken = {
      getItem: () => {
        throw new Error("denied");
      },
      setItem: () => {
        throw new Error("quota");
      },
      removeItem: () => {
        throw new Error("denied");
      },
    };
    expect(loadOptions(broken, "k", "movies")).toEqual(DEFAULT_OPTIONS);
    expect(() => saveOptions(broken, "k", DEFAULT_OPTIONS)).not.toThrow();
    expect(loadOptions(null, "k", "movies")).toEqual(DEFAULT_OPTIONS);
    const garbage = new MemoryStorage();
    garbage.setItem("k", "{not json");
    expect(loadOptions(garbage, "k", "movies")).toEqual(DEFAULT_OPTIONS);
  });
});
