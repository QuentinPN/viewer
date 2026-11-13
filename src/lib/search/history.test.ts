import { describe, expect, it } from "vitest";
import {
  addSearch,
  historyKey,
  readHistory,
  SEARCH_HISTORY_MAX,
  saveHistory,
} from "./history";

function memoryStorage(): Storage {
  const data = new Map<string, string>();
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

describe("addSearch", () => {
  it("puts the cleaned term first", () => {
    expect(addSearch(["alien"], "  le   parrain ")).toEqual([
      "le parrain",
      "alien",
    ]);
  });

  it("replaces an older spelling, case and accents aside", () => {
    expect(addSearch(["Amelie", "dune"], "amélie")).toEqual(["amélie", "dune"]);
  });

  it("ignores a term too short to be searched", () => {
    const history = ["dune"];
    expect(addSearch(history, " a ")).toBe(history);
    expect(addSearch(history, "")).toBe(history);
  });

  it("keeps at most SEARCH_HISTORY_MAX terms", () => {
    let history: string[] = [];
    for (let i = 0; i < SEARCH_HISTORY_MAX + 3; i++) {
      history = addSearch(history, `terme ${i}`);
    }
    expect(history).toHaveLength(SEARCH_HISTORY_MAX);
    expect(history[0]).toBe(`terme ${SEARCH_HISTORY_MAX + 2}`);
  });
});

describe("history storage", () => {
  const key = historyKey("srv", "user");

  it("is kept per server and user", () => {
    expect(historyKey("srv", "a")).not.toBe(historyKey("srv", "b"));
    expect(historyKey("a", "u")).not.toBe(historyKey("b", "u"));
  });

  it("reads back what was saved", () => {
    const s = memoryStorage();
    saveHistory(s, key, ["dune", "alien"]);
    expect(readHistory(s, key)).toEqual(["dune", "alien"]);
  });

  it("clears by removing the key", () => {
    const s = memoryStorage();
    saveHistory(s, key, ["dune"]);
    saveHistory(s, key, []);
    expect(s.getItem(key)).toBeNull();
    expect(readHistory(s, key)).toEqual([]);
  });

  it("cleans stored values again and survives garbage", () => {
    const s = memoryStorage();
    s.setItem(key, JSON.stringify(["  dune ", 3, "x", "DUNE", "alien"]));
    expect(readHistory(s, key)).toEqual(["dune", "alien"]);
    s.setItem(key, "{nope");
    expect(readHistory(s, key)).toEqual([]);
    s.setItem(key, '{"a":1}');
    expect(readHistory(s, key)).toEqual([]);
    expect(readHistory(null, key)).toEqual([]);
  });

  it("survives a storage that throws", () => {
    const broken = {
      ...memoryStorage(),
      getItem: () => {
        throw new Error("refused");
      },
      setItem: () => {
        throw new Error("quota");
      },
    } as Storage;
    expect(readHistory(broken, key)).toEqual([]);
    expect(() => saveHistory(broken, key, ["dune"])).not.toThrow();
  });
});
