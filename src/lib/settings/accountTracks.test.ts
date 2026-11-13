import { describe, expect, it } from "vitest";
import {
  type AccountTracks,
  accountAudioLabel,
  accountSubtitleLabel,
  languageName,
  trackSources,
} from "./accountTracks";

const none: AccountTracks = {
  audioLanguages: [],
  playDefaultAudio: false,
  subtitleLanguages: [],
  subtitleMode: null,
};

describe("account audio and subtitle preferences", () => {
  it("names languages in any Emby spelling", () => {
    expect(languageName("fre")).toBe("français");
    expect(languageName("FRA")).toBe("français");
    expect(languageName("en")).toBe("anglais");
    expect(languageName("tlh")).toBe("tlh");
  });

  it("describes the account's audio", () => {
    expect(accountAudioLabel(none)).toBe(
      "Aucune préférence (choix du serveur)",
    );
    expect(accountAudioLabel({ ...none, audioLanguages: ["fre", "eng"] })).toBe(
      "Français, puis anglais",
    );
    expect(
      accountAudioLabel({
        ...none,
        playDefaultAudio: true,
        audioLanguages: ["jpn"],
      }),
    ).toBe("Piste par défaut du fichier, sinon japonais");
  });

  it("describes the account's subtitles", () => {
    expect(accountSubtitleLabel(none)).toBe(
      "Aucune préférence (choix du serveur)",
    );
    expect(
      accountSubtitleLabel({
        ...none,
        subtitleMode: "OnlyForced",
        subtitleLanguages: ["fre"],
      }),
    ).toBe("Forcés uniquement, en français");
    expect(
      accountSubtitleLabel({
        ...none,
        subtitleMode: "None",
        subtitleLanguages: ["fre"],
      }),
    ).toBe("Aucun");
    expect(accountSubtitleLabel({ ...none, subtitleMode: "Always" })).toBe(
      "Toujours",
    );
  });

  it("lets a local choice override the account, audio and subtitles apart", () => {
    expect(
      trackSources({
        audioLanguage: null,
        subtitleLanguage: null,
        subtitleMode: "server",
      }),
    ).toEqual({ audio: "account", subtitles: "account" });
    expect(
      trackSources({
        audioLanguage: "eng",
        subtitleLanguage: null,
        subtitleMode: "server",
      }),
    ).toEqual({ audio: "local", subtitles: "account" });
    expect(
      trackSources({
        audioLanguage: null,
        subtitleLanguage: "fre",
        subtitleMode: "off",
      }),
    ).toEqual({ audio: "account", subtitles: "local" });
  });
});
