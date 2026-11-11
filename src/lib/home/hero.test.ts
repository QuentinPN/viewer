import { describe, expect, it } from "vitest";
import type { HeroItem } from "../emby/types";
import { heroMeta, keptIndex, playLabel, playName, stepIndex } from "./hero";

function hero(id: string, extra: Partial<HeroItem> = {}): HeroItem {
  return {
    detail: {
      id,
      name: `Titre ${id}`,
      itemType: "Movie",
      year: 2021,
      runtimeMinutes: null,
      poster: null,
      aspectRatio: null,
      played: false,
      unplayedCount: null,
      favorite: false,
    },
    reason: "latest",
    backdrop: { src: "b", srcset: "b 960w" },
    logo: null,
    overview: null,
    playId: id,
    episode: null,
    resume: null,
    ...extra,
  };
}

describe("stepIndex", () => {
  it("wraps around both ends", () => {
    expect(stepIndex(0, 1, 5)).toBe(1);
    expect(stepIndex(4, 1, 5)).toBe(0);
    expect(stepIndex(0, -1, 5)).toBe(4);
    expect(stepIndex(0, 1, 0)).toBe(0);
  });
});

describe("keptIndex", () => {
  const items = [hero("1"), hero("2"), hero("3")];
  it("keeps the title shown when it is still listed", () => {
    expect(keptIndex(items, "3", 0)).toBe(2);
  });
  it("stays in place, within bounds, when it is gone", () => {
    expect(keptIndex(items, "9", 1)).toBe(1);
    expect(keptIndex(items, "9", 7)).toBe(2);
    expect(keptIndex([], "9", 3)).toBe(0);
  });
});

describe("labels", () => {
  it("resumes a started title, plays a new one, nothing without target", () => {
    expect(playLabel(hero("1"))).toBe("Lecture");
    expect(
      playLabel(hero("1", { resume: { positionSeconds: 60, fraction: 0.1 } })),
    ).toBe("Reprendre");
    expect(playLabel(hero("1", { playId: null }))).toBeNull();
    expect(playName(hero("1", { playId: null }))).toBeNull();
  });

  it("names the episode a series plays", () => {
    const series = hero("7", {
      detail: { ...hero("7").detail, name: "Dark", itemType: "Series" },
      playId: "70",
      episode: { name: "Secrets", seasonIndex: 1, index: 1 },
    });
    expect(heroMeta(series)).toBe("S1 · É1 · Secrets");
    expect(playName(series)).toBe("Lecture Dark, S1 · É1 · Secrets");
    expect(
      heroMeta(
        hero("8", { episode: { name: "Bonus", seasonIndex: 0, index: 2 } }),
      ),
    ).toBe("Spécial 2 · Bonus");
  });

  it("shows the year of a film", () => {
    expect(heroMeta(hero("1"))).toBe("2021");
    expect(playName(hero("1"))).toBe("Lecture Titre 1");
    expect(
      heroMeta(hero("1", { detail: { ...hero("1").detail, year: null } })),
    ).toBe("");
  });
});
