import { describe, expect, it } from "vitest";
import {
  canFallBack,
  formatRemaining,
  formatTime,
  openFailure,
  playbackHeading,
  playMethodLabel,
  trackLabel,
} from "./format";
import type { Track } from "./types";

describe("transcode fallback", () => {
  it("only retries a direct source that never played", () => {
    expect(canFallBack(false, "directPlay")).toBe(true);
    expect(canFallBack(false, "directStream")).toBe(true);
    expect(canFallBack(false, "transcode")).toBe(false);
    expect(canFallBack(true, "directPlay")).toBe(false);
  });

  it("explains the failure in French, with the player's detail last", () => {
    expect(openFailure()).toBe("Le lecteur n'a pas pu ouvrir ce fichier.");
    expect(openFailure("loading failed")).toBe(
      "Le lecteur n'a pas pu ouvrir ce fichier. Détail technique : loading failed.",
    );
  });
});

const track = (over: Partial<Track>): Track => ({
  id: 1,
  kind: "audio",
  title: null,
  lang: null,
  codec: null,
  selected: false,
  external: false,
  default: false,
  ...over,
});

describe("formatRemaining", () => {
  it("counts down with a minus sign, never below zero", () => {
    expect(formatRemaining(60, 3725)).toBe("−1:01:05");
    expect(formatRemaining(90, 60)).toBe("−0:00");
    expect(formatRemaining(0, Number.NaN)).toBe("−0:00");
  });
});

describe("playbackHeading", () => {
  const title = {
    name: "On va carrément se battre en duel ?",
    seriesName: "Aharen-san wa Hakarenai",
    seasonIndex: 1,
    index: 12,
    indexEnd: null,
  };

  it("puts the series and the episode code above an episode", () => {
    expect(playbackHeading(title)).toEqual({
      kicker: "Aharen-san wa Hakarenai · S1 · É12",
      name: "On va carrément se battre en duel ?",
    });
    expect(playbackHeading({ ...title, seasonIndex: 0, index: 3 }).kicker).toBe(
      "Aharen-san wa Hakarenai · Spécial 3",
    );
  });

  it("shows a film by its name alone", () => {
    expect(
      playbackHeading({
        name: "2 Coeurs",
        seriesName: null,
        seasonIndex: null,
        index: null,
        indexEnd: null,
      }),
    ).toEqual({ kicker: null, name: "2 Coeurs" });
  });

  it("falls back to Lecture without a title", () => {
    expect(playbackHeading(null)).toEqual({ kicker: null, name: "Lecture" });
  });
});

describe("formatTime", () => {
  it("formats minutes and hours", () => {
    expect(formatTime(0)).toBe("0:00");
    expect(formatTime(65.9)).toBe("1:05");
    expect(formatTime(3600 + 62)).toBe("1:01:02");
  });

  it("handles invalid input", () => {
    expect(formatTime(null)).toBe("0:00");
    expect(formatTime(Number.NaN)).toBe("0:00");
    expect(formatTime(-3)).toBe("0:00");
  });
});

describe("trackLabel", () => {
  it("translates known languages and joins parts", () => {
    expect(
      trackLabel(track({ lang: "fre", codec: "ass", title: "Complets" })),
    ).toBe("Complets · Français · ASS");
  });

  it("translates BCP 47 tags through their primary language", () => {
    expect(
      trackLabel(track({ lang: "fr-FR", codec: "ass", title: "CC" })),
    ).toBe("CC · Français · ASS");
    expect(trackLabel(track({ lang: "pt-BR" }))).toBe("Portugais");
  });

  it("falls back to the track number and marks external tracks", () => {
    expect(trackLabel(track({ id: 3 }))).toBe("Piste 3");
    expect(trackLabel(track({ lang: "xyz", external: true }))).toBe(
      "xyz (externe)",
    );
  });
});

describe("playMethodLabel", () => {
  it("names the server's decision in French with the container", () => {
    expect(
      playMethodLabel({
        method: "directPlay",
        container: "mkv",
        transcodeReasons: null,
      }),
    ).toBe("Lecture directe · MKV");
    expect(
      playMethodLabel({
        method: "transcode",
        container: null,
        transcodeReasons: null,
      }),
    ).toBe("Transcodage");
    expect(
      playMethodLabel({
        method: "directStream",
        container: "mp4",
        transcodeReasons: null,
      }),
    ).toBe("Flux direct · MP4");
  });

  it("shows why the server transcodes instead of the container", () => {
    expect(
      playMethodLabel({
        method: "transcode",
        container: "ts",
        transcodeReasons: "débit trop élevé",
      }),
    ).toBe("Transcodage · débit trop élevé");
  });
});
