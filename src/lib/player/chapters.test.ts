import { describe, expect, it } from "vitest";
import type { Chapter } from "../emby/types";
import { chapterIndex, chapterMarks, chapterTarget } from "./chapters";

// Starts of `/Users/{Id}/Items/30207` on Emby 4.10.2.0, in seconds.
const chapters: Chapter[] = [
  { start: 0, name: "Chapitre 1" },
  { start: 487.57, name: "Chapitre 2" },
  { start: 726.558, name: "Chapitre 3" },
  { start: 1059.14, name: "Chapitre 4" },
];

describe("chapterIndex", () => {
  it("finds the chapter playing", () => {
    expect(chapterIndex(chapters, 0)).toBe(0);
    expect(chapterIndex(chapters, 487)).toBe(0);
    expect(chapterIndex(chapters, 500)).toBe(1);
    expect(chapterIndex(chapters, 5000)).toBe(3);
  });

  it("counts a seek that lands just before a start as in that chapter", () => {
    expect(chapterIndex(chapters, 487.2)).toBe(1);
  });

  it("is -1 before the first chapter or without chapters", () => {
    expect(chapterIndex([{ start: 30, name: "A" }], 10)).toBe(-1);
    expect(chapterIndex([], 10)).toBe(-1);
  });
});

describe("chapterTarget", () => {
  it("goes to the next start, and nowhere after the last chapter", () => {
    expect(chapterTarget(chapters, 0, 1)).toBe(487.57);
    expect(chapterTarget(chapters, 600, 1)).toBe(726.558);
    // Right after landing on a start, the next key goes on.
    expect(chapterTarget(chapters, 487.57, 1)).toBe(726.558);
    expect(chapterTarget(chapters, 1100, 1)).toBeNull();
  });

  it("goes back to the start of the chapter, then to the one before", () => {
    expect(chapterTarget(chapters, 800, -1)).toBe(726.558);
    expect(chapterTarget(chapters, 728, -1)).toBe(487.57);
    expect(chapterTarget(chapters, 726.558, -1)).toBe(487.57);
  });

  it("stays at the start of the first chapter", () => {
    expect(chapterTarget(chapters, 100, -1)).toBe(0);
    expect(chapterTarget(chapters, 1, -1)).toBe(0);
    expect(chapterTarget([{ start: 30, name: "A" }], 10, -1)).toBe(0);
  });

  it("does nothing without chapters", () => {
    expect(chapterTarget([], 100, 1)).toBeNull();
    expect(chapterTarget([], 100, -1)).toBeNull();
  });
});

describe("chapterMarks", () => {
  it("marks every start but the file's, in percent", () => {
    const marks = chapterMarks(chapters, 6103.389);
    expect(marks).toHaveLength(3);
    expect(marks[0]).toBeCloseTo(7.99, 2);
    expect(marks[2]).toBeCloseTo(17.35, 2);
  });

  it("has none before the duration is known, and none past the end", () => {
    expect(chapterMarks(chapters, 0)).toEqual([]);
    expect(chapterMarks(chapters, 600)).toHaveLength(1);
  });
});
