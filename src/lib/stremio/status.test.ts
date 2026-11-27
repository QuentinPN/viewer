import { describe, expect, it } from "vitest";
import { addonLabel, statusLabel, stremioUsable } from "./status";

describe("statusLabel", () => {
  it("reads each state in French", () => {
    expect(statusLabel({ state: "signed_out", addons: null })).toBe(
      "Déconnecté",
    );
    expect(statusLabel({ state: "signed_in", addons: [] })).toBe("Connecté");
    expect(statusLabel({ state: "reauth_required", addons: null })).toBe(
      "Session Stremio expirée. Reconnecte-toi.",
    );
    expect(statusLabel({ state: "unreachable", addons: null })).toBe(
      "Stremio ne répond pas.",
    );
  });
});

describe("addonLabel", () => {
  it("names the kinds the addon serves", () => {
    expect(addonLabel({ name: "Comet", movie: true, series: true })).toBe(
      "Comet — Films · Séries",
    );
    expect(addonLabel({ name: "Comet", movie: true, series: false })).toBe(
      "Comet — Films",
    );
    expect(addonLabel({ name: "Comet", movie: false, series: true })).toBe(
      "Comet — Séries",
    );
  });

  it("keeps the bare name when no kind is known", () => {
    expect(addonLabel({ name: "Comet", movie: false, series: false })).toBe(
      "Comet",
    );
  });
});

describe("stremioUsable", () => {
  it("is true once an account is linked, even with an expired session", () => {
    expect(stremioUsable("signed_in")).toBe(true);
    expect(stremioUsable("reauth_required")).toBe(true);
  });

  it("is false signed out, unreachable or before the status is known", () => {
    expect(stremioUsable("signed_out")).toBe(false);
    expect(stremioUsable("unreachable")).toBe(false);
    expect(stremioUsable(null)).toBe(false);
  });
});
