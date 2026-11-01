import { describe, expect, it } from "vitest";
import {
  cacheUsage,
  clearedLabel,
  imageCount,
  sessionReads,
  usageRatio,
} from "./format";

const MIB = 1024 * 1024;
const stats = (bytes: number, hits = 0, misses = 0) => ({
  entries: 3,
  bytes,
  quotaBytes: 512 * MIB,
  hits,
  misses,
});
// fr-FR groups thousands with a narrow no-break space.
const nb = (s: string) => s.replace(/ /g, " ");

describe("imageCount", () => {
  it("says none, one or many", () => {
    expect(imageCount(0)).toBe("aucune image");
    expect(imageCount(1)).toBe("1 image");
    expect(nb(imageCount(2431))).toBe("2 431 images");
  });
});

describe("cacheUsage", () => {
  it("shows the size and the quota, even when empty", () => {
    expect(cacheUsage(stats(0))).toBe("0 o sur 512 Mo");
    expect(cacheUsage(stats(123 * MIB))).toBe("123 Mo sur 512 Mo");
  });
});

describe("usageRatio", () => {
  it("stays between 0 and 1", () => {
    expect(usageRatio(stats(128 * MIB))).toBe(0.25);
    expect(usageRatio(stats(600 * MIB))).toBe(1);
    expect(usageRatio({ ...stats(10), quotaBytes: 0 })).toBe(0);
  });
});

describe("sessionReads", () => {
  it("counts reads from the disk and from the server", () => {
    expect(sessionReads(stats(0))).toBe(
      "Aucune image affichée depuis le lancement.",
    );
    expect(sessionReads(stats(0, 12, 1))).toBe(
      "Depuis le lancement : 12 images lues sur ce poste, 1 image demandée au serveur.",
    );
    expect(sessionReads(stats(0, 0, 3))).toBe(
      "Depuis le lancement : aucune image lue sur ce poste, 3 images demandées au serveur.",
    );
  });
});

describe("clearedLabel", () => {
  it("announces what was freed", () => {
    expect(clearedLabel({ entries: 12, bytes: 3.5 * MIB })).toBe(
      "Cache vidé : 12 images, 3,5 Mo libérés.",
    );
    expect(clearedLabel({ entries: 0, bytes: 0 })).toBe(
      "Le cache était déjà vide.",
    );
  });
});
