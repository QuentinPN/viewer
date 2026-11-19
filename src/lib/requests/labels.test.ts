import { describe, expect, it } from "vitest";
import {
  isPlainHttp,
  mediaStatusText,
  percent,
  quotaLine,
  quotaUsageLine,
  requestStatusLabel,
  roleLabel,
  sessionName,
  stateLine,
  statusLabel,
  statusTone,
} from "./labels";
import type { AuthSession, RequestQuota, UrStatus, Viewer } from "./types";

describe("status labels", () => {
  it("names every title status as the spec", () => {
    expect(statusLabel("available")).toBe("Disponible");
    expect(statusLabel("partial")).toBe("Partiel");
    expect(statusLabel("downloading")).toBe("Téléchargement");
    expect(statusLabel("requested")).toBe("Demandé");
    expect(statusLabel("pending")).toBe("En attente");
    expect(statusLabel("declined")).toBe("Refusé");
  });

  it("names every request status as the spec", () => {
    expect(requestStatusLabel("pending")).toBe("En attente");
    expect(requestStatusLabel("approved")).toBe("Approuvée");
    expect(requestStatusLabel("sent")).toBe("Envoyée");
    expect(requestStatusLabel("available")).toBe("Disponible");
    expect(requestStatusLabel("declined")).toBe("Refusée");
    expect(requestStatusLabel("cancelled")).toBe("Annulée");
    expect(requestStatusLabel("failed")).toBe("Échec");
  });

  it("gives each status its tone", () => {
    expect(statusTone("available")).toBe("success");
    expect(statusTone("downloading")).toBe("accent");
    expect(statusTone("pending")).toBe("warning");
    expect(statusTone("declined")).toBe("danger");
    expect(statusTone("failed")).toBe("danger");
    expect(statusTone("cancelled")).toBe("muted");
  });

  it("adds the progress to a download only", () => {
    expect(mediaStatusText("downloading", 0.6)).toBe("Téléchargement · 60 %");
    expect(mediaStatusText("downloading", null)).toBe("Téléchargement");
    expect(mediaStatusText("partial", 0.6)).toBe("Partiel");
    expect(percent(1.4)).toBe("100 %");
    expect(percent(undefined)).toBeNull();
  });

  it("names the roles", () => {
    expect(roleLabel("member")).toBe("Membre");
    expect(roleLabel("owner")).toBe("Propriétaire");
  });
});

describe("quota", () => {
  const quota: RequestQuota = {
    movies: { limit: 10, days: 7, used: 2, remaining: 8 },
    seasons: { limit: 5, days: 7, used: 1, remaining: 4 },
    exempt: false,
  };

  it("reads the remaining quota", () => {
    expect(quotaLine(quota)).toBe(
      "Quota : 8 films sur 10 restants · 4 saisons sur 5 · sur 7 jours",
    );
    expect(quotaUsageLine(quota, "tv")).toBe(
      "4 saisons sur 5 restantes · sur 7 jours",
    );
    expect(quotaUsageLine(quota, "movie")).toBe(
      "8 films sur 10 restants · sur 7 jours",
    );
  });

  it("is hidden when exempt and split when the periods differ", () => {
    expect(quotaLine({ ...quota, exempt: true })).toBeNull();
    expect(
      quotaLine({
        ...quota,
        movies: { limit: 5, days: 7, used: 4, remaining: 1 },
        seasons: { ...quota.seasons, days: 30 },
      }),
    ).toBe(
      "Quota : 1 film sur 5 restant sur 7 jours · 4 saisons sur 5 sur 30 jours",
    );
  });
});

describe("sessions and address", () => {
  it("names a device by its name, else its user agent", () => {
    const session: AuthSession = {
      id: "s1",
      deviceName: "PC-SALON (Windows)",
      userAgent: "Viewer/1.4.0",
      createdAt: "2026-11-10T18:02:11.000Z",
      lastSeenAt: "2026-11-19T09:41:27.000Z",
      expiresAt: "2026-12-19T09:41:27.000Z",
      current: true,
    };
    expect(sessionName(session)).toBe("PC-SALON (Windows)");
    expect(sessionName({ ...session, deviceName: null })).toBe(
      "Viewer/1.4.0",
    );
  });

  it("flags a plain HTTP address", () => {
    expect(isPlainHttp("http://192.168.1.14:3000")).toBe(true);
    expect(isPlainHttp(" HTTP://nas.local")).toBe(true);
    expect(isPlainHttp("https://request.example")).toBe(false);
    expect(isPlainHttp(null)).toBe(false);
  });
});

describe("state line", () => {
  const base: UrStatus = {
    state: "ready",
    address: "https://request.example",
    viewer: { name: "Quentin", role: "member" } as Viewer,
    apiVersion: "1.0.0",
    missing: [],
    message: null,
  };

  it("names the account, its role and the API when connected", () => {
    expect(stateLine(base)).toEqual({
      text: "Connecté en tant que Quentin · Membre · API 1.0.0",
      tone: "success",
    });
  });

  it("names the other states", () => {
    expect(stateLine({ ...base, state: "signedOut" }).text).toBe("Déconnecté");
    expect(stateLine({ ...base, state: "rejected" }).tone).toBe("danger");
    expect(stateLine({ ...base, state: "tooOld" }).text).toBe(
      "Serveur Request trop ancien",
    );
  });
});
