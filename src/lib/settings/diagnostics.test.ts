import { describe, expect, it } from "vitest";
import { openLogDirError } from "./diagnostics";

describe("openLogDirError", () => {
  it("shows the reason given by Rust", () => {
    expect(
      openLogDirError("dossier des journaux indisponible sur ce poste"),
    ).toBe(
      "Impossible d'ouvrir le dossier des journaux : dossier des journaux indisponible sur ce poste.",
    );
  });

  it("falls back on a generic reason", () => {
    for (const e of [
      undefined,
      null,
      "",
      "  ",
      new Error("x"),
      { kind: "x" },
    ]) {
      expect(openLogDirError(e)).toBe(
        "Impossible d'ouvrir le dossier des journaux : erreur inattendue.",
      );
    }
  });
});
