import { describe, expect, it } from "vitest";
import { FITS, fitBadge } from "./videofit";

describe("video fit", () => {
  it("offers original, fill and stretch in order", () => {
    expect(FITS.map((f) => f.fit)).toEqual(["original", "fill", "stretch"]);
  });
  it("shows a badge only off original", () => {
    expect(fitBadge("original")).toBeNull();
    expect(fitBadge("fill")).toBe("Remplir");
    expect(fitBadge("stretch")).toBe("Étiré");
  });
});
