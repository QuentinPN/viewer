import { describe, expect, it } from "vitest";
import { DEFAULT_OPTIONS, withSort } from "./options";
import {
  canReset,
  counterLabel,
  decadeChoice,
  decadesOf,
  edgeLetter,
  favoritesLabel,
  filtersActive,
  filtersLabel,
  firstVisible,
  jumpLimit,
  LETTERS,
  letterIndex,
  letterLabel,
  letterOf,
  nextWatch,
  sortActive,
  sortLabel,
  stepLetter,
  watchLabel,
} from "./rails";

describe("letters", () => {
  it("lists # then A to Z", () => {
    expect(LETTERS).toHaveLength(27);
    expect(LETTERS[0]).toBe("#");
    expect(LETTERS.at(-1)).toBe("Z");
  });

  it("files titles without accent nor case, others under #", () => {
    expect(letterOf("matrix")).toBe("M");
    expect(letterOf("Été meurtrier")).toBe("E");
    expect(letterOf("Œdipe roi")).toBe("O");
    expect(letterOf("ça")).toBe("C");
    expect(letterOf("  Zodiac")).toBe("Z");
    expect(letterOf("2 Coeurs")).toBe("#");
    expect(letterOf("¡Three Amigos!")).toBe("#");
    expect(letterOf("千と千尋")).toBe("#");
    expect(letterOf("")).toBe("#");
  });

  it("keeps the first position and id of each letter in grid order", () => {
    const index = letterIndex([
      { id: "z1", name: "Zodiac" },
      { id: "a1", name: "Alien" },
      { id: "a2", name: "Amélie" },
      { id: "n1", name: "1917" },
      { id: "e1", name: "Élysium" },
    ]);
    expect(index.get("Z")).toEqual({ position: 0, id: "z1" });
    expect(index.get("A")).toEqual({ position: 1, id: "a1" });
    expect(index.get("#")).toEqual({ position: 3, id: "n1" });
    expect(index.get("E")).toEqual({ position: 4, id: "e1" });
    expect(index.has("B")).toBe(false);
  });

  it("steps over letters without titles, and stops at the ends", () => {
    const present = new Set(["#", "C", "M"]);
    expect(stepLetter("C", 1, present)).toBe("M");
    expect(stepLetter("C", -1, present)).toBe("#");
    expect(stepLetter("M", 1, present)).toBe("M");
    expect(stepLetter("#", -1, present)).toBe("#");
    expect(stepLetter("C", 1, null)).toBe("D");
    expect(edgeLetter(1, present)).toBe("#");
    expect(edgeLetter(-1, present)).toBe("M");
    expect(edgeLetter(1, new Set())).toBeNull();
    expect(edgeLetter(-1, null)).toBe("Z");
  });

  it("names each letter for screen readers", () => {
    expect(letterLabel("M", true)).toBe("Aller à la lettre M");
    expect(letterLabel("Q", false)).toBe("Aucun titre à la lettre Q");
    expect(letterLabel("#", true)).toContain("chiffre ou un symbole");
  });
});

describe("jumpLimit", () => {
  it("loads up to the end of the letter's page, in bounded requests", () => {
    expect(jumpLimit(10, 60, 60, 200)).toBe(0);
    expect(jumpLimit(60, 60, 60, 200)).toBe(60);
    expect(jumpLimit(130, 60, 60, 200)).toBe(120);
    expect(jumpLimit(1000, 60, 60, 200)).toBe(200);
    // A page ended early (dropped items): still reaches the page end.
    expect(jumpLimit(119, 57, 60, 200)).toBe(63);
  });
});

describe("icon rail", () => {
  it("offers Réinitialiser only away from the defaults", () => {
    expect(canReset(DEFAULT_OPTIONS)).toBe(false);
    expect(canReset({ ...DEFAULT_OPTIONS, descending: true })).toBe(true);
    expect(canReset(withSort(DEFAULT_OPTIONS, "year"))).toBe(true);
    expect(canReset({ ...DEFAULT_OPTIONS, decade: 1990 })).toBe(true);
    expect(canReset({ ...DEFAULT_OPTIONS, favorites: true })).toBe(true);
  });

  it("draws an icon in accent when its choice narrows or reorders", () => {
    expect(sortActive(DEFAULT_OPTIONS)).toBe(false);
    expect(sortActive({ ...DEFAULT_OPTIONS, descending: true })).toBe(true);
    expect(sortActive(withSort(DEFAULT_OPTIONS, "rating"))).toBe(true);
    expect(filtersActive(DEFAULT_OPTIONS)).toBe(false);
    expect(filtersActive({ ...DEFAULT_OPTIONS, genre: "Drame" })).toBe(true);
    expect(filtersActive({ ...DEFAULT_OPTIONS, decade: 1980 })).toBe(true);
  });

  it("labels each icon with its state", () => {
    expect(sortLabel(DEFAULT_OPTIONS)).toBe("Tri : Titre, A → Z");
    expect(sortLabel(withSort(DEFAULT_OPTIONS, "dateAdded"))).toBe(
      "Tri : Date d'ajout, Plus récents",
    );
    expect(watchLabel("movies", "unplayed")).toBe("Visionnage : Non vus");
    expect(watchLabel("tvShows", "all")).toBe("Visionnage : Tous");
    expect(favoritesLabel(true)).toBe("Favoris seulement : oui");
    expect(favoritesLabel(false)).toBe("Favoris seulement : non");
    expect(filtersLabel(DEFAULT_OPTIONS)).toBe("Filtres : aucun");
    expect(
      filtersLabel({ ...DEFAULT_OPTIONS, genre: "Drame", decade: 1990 }),
    ).toBe("Filtres : Drame, années 1990");
    expect(filtersLabel({ ...DEFAULT_OPTIONS, decade: 2010 })).toBe(
      "Filtres : années 2010",
    );
  });

  it("cycles the watched filter, En cours for films only", () => {
    expect(nextWatch("movies", "all")).toBe("unplayed");
    expect(nextWatch("movies", "played")).toBe("inProgress");
    expect(nextWatch("movies", "inProgress")).toBe("all");
    expect(nextWatch("tvShows", "played")).toBe("all");
    expect(nextWatch("tvShows", "inProgress")).toBe("all");
  });

  it("turns years into decades, newest first", () => {
    expect(decadesOf([1994, 2020, 1999, 2021, 1987])).toEqual([
      2020, 1990, 1980,
    ]);
    expect(decadesOf([])).toEqual([]);
    expect(decadeChoice(1990)).toBe("Années 1990");
  });
});

describe("counter", () => {
  it("shows position and total once both are known", () => {
    expect(counterLabel(11, 239)).toBe("12 | 239");
    expect(counterLabel(0, 1)).toBe("1 | 1");
    expect(counterLabel(null, 239)).toBeNull();
    expect(counterLabel(3, null)).toBeNull();
    expect(counterLabel(0, 0)).toBeNull();
    expect(counterLabel(400, 239)).toBe("239 | 239");
  });

  it("finds the first card still in view", () => {
    // Rows of three cards, 300 px high with a 30 px gap.
    const box = (i: number) => ({ top: Math.floor(i / 3) * 330, height: 300 });
    expect(firstVisible(9, box, 0)).toBe(0);
    expect(firstVisible(9, box, 299)).toBe(0);
    expect(firstVisible(9, box, 300)).toBe(3);
    expect(firstVisible(9, box, 700)).toBe(6);
    expect(firstVisible(9, box, 5000)).toBeNull();
    expect(firstVisible(0, box, 0)).toBeNull();
  });
});
