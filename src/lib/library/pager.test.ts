import { describe, expect, it } from "vitest";
import type { MediaItem } from "../emby/types";
import {
  appendPage,
  countLabel,
  emptyPager,
  itemSubtitle,
  PAGE_SIZE,
  refreshItems,
} from "./pager";

const item = (id: number, extra: Partial<MediaItem> = {}): MediaItem => ({
  id: String(id),
  name: `Film ${id}`,
  itemType: "Movie",
  year: null,
  runtimeMinutes: null,
  poster: null,
  aspectRatio: null,
  played: false,
  unplayedCount: null,
  ...extra,
});

const page = (start: number, count: number, total: number) => ({
  startIndex: start,
  nextIndex: start + count,
  total,
  items: Array.from({ length: count }, (_, i) => item(start + i)),
});

describe("appendPage", () => {
  it("loads pages in order until the total is reached", () => {
    let s = appendPage(emptyPager(), page(0, PAGE_SIZE, 130));
    expect(s.done).toBe(false);
    expect(s.next).toBe(PAGE_SIZE);
    s = appendPage(s, page(PAGE_SIZE, PAGE_SIZE, 130));
    s = appendPage(s, page(2 * PAGE_SIZE, 10, 130));
    expect(s.items).toHaveLength(130);
    expect(s.done).toBe(true);
  });

  it("keeps the first total when the server answers 0 past the end", () => {
    let s = appendPage(emptyPager(), page(0, PAGE_SIZE, 200));
    s = appendPage(s, {
      startIndex: PAGE_SIZE,
      nextIndex: PAGE_SIZE,
      total: 0,
      items: [],
    });
    expect(s.total).toBe(200);
    expect(s.done).toBe(true);
  });

  it("ignores stale or duplicate pages", () => {
    const s = appendPage(emptyPager(), page(0, PAGE_SIZE, 200));
    expect(appendPage(s, page(0, PAGE_SIZE, 200))).toBe(s);
    expect(appendPage(s, page(3 * PAGE_SIZE, PAGE_SIZE, 200))).toBe(s);
    // Items shifted on the server: an id already shown is not repeated.
    const shifted = {
      ...page(PAGE_SIZE, PAGE_SIZE, 200),
      items: [item(PAGE_SIZE - 1), ...page(PAGE_SIZE, 5, 200).items],
    };
    expect(appendPage(s, shifted).items).toHaveLength(PAGE_SIZE + 5);
  });

  it("follows the server index when Rust dropped unusable items", () => {
    const short = { ...page(0, PAGE_SIZE, 200), items: [item(0)] };
    const s = appendPage(emptyPager(), short);
    expect(s.items).toHaveLength(1);
    expect(s.next).toBe(PAGE_SIZE);
    expect(s.done).toBe(false);
  });

  it("ends on an empty library", () => {
    const s = appendPage(emptyPager(), page(0, 0, 0));
    expect(s).toEqual({ items: [], next: 0, total: 0, done: true });
  });
});

describe("refreshItems", () => {
  it("puts fresh copies in place, order and paging unchanged", () => {
    const s = appendPage(emptyPager(), page(0, 3, 200));
    const fresh = [item(2, { unplayedCount: 4 }), item(1, { played: true })];
    const r = refreshItems(s, fresh);
    expect(r.items.map((i) => i.id)).toEqual(["0", "1", "2"]);
    expect(r.items[1]?.played).toBe(true);
    expect(r.items[2]?.unplayedCount).toBe(4);
    expect({ ...r, items: [] }).toEqual({ ...s, items: [] });
  });

  it("neither adds nor drops items", () => {
    const s = appendPage(emptyPager(), page(0, 2, 200));
    expect(refreshItems(s, [item(9)])).toBe(s);
    expect(refreshItems(s, []).items).toHaveLength(2);
  });
});

describe("labels", () => {
  it("formats year and runtime", () => {
    expect(itemSubtitle(item(1, { year: 2020, runtimeMinutes: 101 }))).toBe(
      "2020 · 1 h 41",
    );
    expect(itemSubtitle(item(1, { runtimeMinutes: 45 }))).toBe("45 min");
    expect(itemSubtitle(item(1))).toBe("");
  });

  it("counts in French", () => {
    expect(countLabel(1, "movies")).toBe("1 film");
    expect(countLabel(0, "tvShows")).toBe("0 série");
    expect(countLabel(707, "movies")).toBe("707 films");
    expect(countLabel(1500, "tvShows")).toMatch(/^1\s500 séries$/);
  });
});
