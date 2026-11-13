import { describe, expect, it } from "vitest";
import {
  canApply,
  QUALITY_IDLE,
  qualityChoices,
  qualityLabel,
  qualityNote,
} from "./quality";

describe("quality during playback", () => {
  it("offers the maximum first, then caps highest first", () => {
    expect(qualityChoices([4_000_000, 40_000_000])).toEqual([
      null,
      40_000_000,
      4_000_000,
    ]);
    expect(qualityLabel(null)).toBe("Maximale (automatique)");
  });

  it("applies only another cap, once at a time", () => {
    expect(canApply(null, null, QUALITY_IDLE)).toBe(false);
    expect(canApply(4_000_000, null, QUALITY_IDLE)).toBe(true);
    expect(canApply(4_000_000, null, { kind: "applying" })).toBe(false);
    expect(canApply(4_000_000, null, { kind: "failed", message: "x" })).toBe(
      true,
    );
  });

  it("words the state", () => {
    expect(qualityNote(QUALITY_IDLE)).toBeNull();
    expect(qualityNote({ kind: "applying" })).toBe("Changement de qualité…");
    expect(
      qualityNote({ kind: "failed", message: "Serveur injoignable." }),
    ).toBe("Qualité inchangée : Serveur injoignable.");
  });
});
