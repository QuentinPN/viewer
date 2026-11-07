import { describe, expect, it } from "vitest";
import {
  AUTOMATIC,
  addressNote,
  capInEffect,
  capLabel,
  capValue,
  mbits,
  parseCap,
} from "./quality";

const CHOICES = [
  40_000_000, 20_000_000, 10_000_000, 8_000_000, 4_000_000, 3_000_000,
  1_500_000,
];

describe("streaming quality labels", () => {
  it("formats caps in Mbit/s, French style", () => {
    expect(mbits(40_000_000)).toBe("40 Mbit/s");
    expect(mbits(1_500_000)).toBe("1,5 Mbit/s");
    expect(capLabel(null)).toBe("Maximale (automatique)");
    expect(capLabel(8_000_000)).toBe("8 Mbit/s");
  });

  it("round-trips select values and drops what is not offered", () => {
    expect(capValue(null)).toBe(AUTOMATIC);
    for (const cap of CHOICES)
      expect(parseCap(capValue(cap), CHOICES)).toBe(cap);
    expect(parseCap(AUTOMATIC, CHOICES)).toBeNull();
    expect(parseCap("5000000", CHOICES)).toBeNull();
    expect(parseCap("", CHOICES)).toBeNull();
  });

  it("says which cap applies and which one played", () => {
    expect(addressNote(true)).toContain("« Réseau local »");
    expect(addressNote(false)).toContain("« Accès distant »");
    expect(capInEffect(null, true)).toBe("Automatique · réseau local");
    expect(capInEffect(4_000_000, false)).toBe("4 Mbit/s · accès distant");
  });
});
