import { describe, expect, it } from "vitest";
import type { MediaItem } from "../emby/types";
import {
  type DetailPage,
  initials,
  MAX_PAGES,
  pageKey,
  personPage,
  pushPage,
  replaceTop,
  titlesCount,
} from "./pages";

function film(id: string): MediaItem {
  return {
    id,
    name: `Film ${id}`,
    itemType: "Movie",
    year: 2020,
    runtimeMinutes: 100,
    poster: null,
    aspectRatio: null,
    played: false,
    unplayedCount: null,
    favorite: false,
  };
}

const item = (id: string): DetailPage => ({
  kind: "item",
  item: film(id),
  via: null,
});

describe("page stack", () => {
  it("pushes over the others and keeps the newest ones", () => {
    let pages: DetailPage[] = [];
    for (let i = 0; i < MAX_PAGES + 3; i++)
      pages = pushPage(pages, item(`${i}`));
    expect(pages).toHaveLength(MAX_PAGES);
    expect(pageKey(pages[0] as DetailPage, 0)).toBe("0:item:3");
    expect(pageKey(pages.at(-1) as DetailPage, 9)).toBe(
      `9:item:${MAX_PAGES + 2}`,
    );
  });

  it("replaces the top page only", () => {
    const pages = replaceTop([item("1"), item("2")], item("3"));
    expect(pages.map((p) => pageKey(p, 0))).toEqual(["0:item:1", "0:item:3"]);
    expect(replaceTop([], item("4"))).toHaveLength(1);
  });
});

describe("personPage", () => {
  it("opens the titles of a person with an id", () => {
    const page = personPage({
      name: "Jacob Elordi",
      role: "Chris",
      id: "30209",
      photo: null,
    });
    expect(page).toMatchObject({
      kind: "titles",
      source: { kind: "person", id: "30209" },
      title: "Jacob Elordi",
    });
    expect(pageKey(page as DetailPage, 1)).toBe("1:titles:person:30209");
  });

  it("has no page without an id", () => {
    expect(
      personPage({ name: "Inconnu", role: null, id: null, photo: null }),
    ).toBeNull();
  });
});

describe("labels", () => {
  it("builds initials from words with letters", () => {
    expect(initials("jacob  elordi")).toBe("JE");
    expect(initials("— Élise")).toBe("É");
    expect(initials("")).toBe("");
  });

  it("counts titles in French", () => {
    expect(titlesCount(0)).toBe("Aucun titre");
    expect(titlesCount(1)).toBe("1 titre");
    expect(titlesCount(12)).toBe("12 titres");
  });
});
