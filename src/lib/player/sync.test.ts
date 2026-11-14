import { describe, expect, it } from "vitest";
import { noticeText, pillText } from "./sync";

describe("pillText", () => {
  it("names the TV alone before any drift arrives", () => {
    expect(pillText("Shield salon", null)).toBe("Lié à Shield salon");
  });

  it("shows the gap as a rounded magnitude", () => {
    expect(pillText("Shield", 40)).toBe("Lié à Shield · ±40 ms");
    expect(pillText("Shield", -12.6)).toBe("Lié à Shield · ±13 ms");
    expect(pillText("Shield", 0)).toBe("Lié à Shield · ±0 ms");
  });
});

describe("noticeText", () => {
  it("speaks to the user for each notice", () => {
    const device = "Shield";
    expect(noticeText({ kind: "following", device })).toBe(
      "Shield suit ta lecture.",
    );
    expect(noticeText({ kind: "lost", device })).toBe(
      "Lien perdu avec Shield. La lecture continue ici.",
    );
    expect(noticeText({ kind: "noAnswer", device })).toBe(
      "Shield ne répond pas.",
    );
    expect(noticeText({ kind: "busy", device })).toBe(
      "Shield suit déjà une autre lecture.",
    );
  });
});
