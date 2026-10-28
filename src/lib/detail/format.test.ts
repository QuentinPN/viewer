import { describe, expect, it } from "vitest";
import type { Episode, ItemDetail, Season, StreamInfo } from "../emby/types";
import { languageName } from "../format/language";
import {
  audioLabel,
  defaultSeason,
  episodeNumber,
  formatBitrate,
  formatDate,
  formatSize,
  metaParts,
  ratingLabel,
  resumeLabel,
  subtitleLabel,
  videoLabel,
  yearsLabel,
} from "./format";

const detail = (over: Partial<ItemDetail>): ItemDetail => ({
  id: "1",
  kind: "movie",
  name: "Film",
  originalTitle: null,
  year: 2020,
  endYear: null,
  continuing: null,
  overview: null,
  tagline: null,
  genres: [],
  studios: [],
  directors: [],
  cast: [],
  officialRating: null,
  communityRating: null,
  criticRating: null,
  runtimeMinutes: null,
  premiereDate: null,
  poster: null,
  backdrop: null,
  logo: null,
  played: false,
  favorite: false,
  resume: null,
  unplayedCount: null,
  media: [],
  ...over,
});

const stream = (over: Partial<StreamInfo>): StreamInfo => ({
  language: null,
  codec: null,
  title: null,
  channels: null,
  isDefault: false,
  isForced: false,
  isExternal: false,
  ...over,
});

const episode = (over: Partial<Episode>): Episode => ({
  id: "1",
  name: "Épisode",
  index: 1,
  indexEnd: null,
  seasonIndex: 1,
  overview: null,
  runtimeMinutes: null,
  premiereDate: null,
  thumb: null,
  played: false,
  resume: null,
  ...over,
});

const season = (index: number, over: Partial<Season> = {}): Season => ({
  id: String(index),
  name: `Saison ${index}`,
  index,
  unplayedCount: 0,
  played: false,
  ...over,
});

describe("languageName", () => {
  it("names ISO 639-1, 639-2/B and /T codes and BCP 47 tags in French", () => {
    for (const code of ["fr", "fre", "fra", "fr-FR", "FRE"])
      expect(languageName(code)).toBe("Français");
    expect(languageName("ger")).toBe("Allemand");
    expect(languageName("chi")).toBe("Chinois");
  });

  it("returns null for undetermined, empty or unknown codes", () => {
    for (const code of ["und", "zxx", "", null, "frf", "xx1", "a"])
      expect(languageName(code)).toBeNull();
  });
});

describe("header", () => {
  it("formats film and series years", () => {
    expect(yearsLabel(detail({}))).toBe("2020");
    expect(
      yearsLabel(detail({ kind: "series", year: 2019, endYear: 2023 })),
    ).toBe("2019–2023");
    expect(
      yearsLabel(detail({ kind: "series", year: 2024, endYear: 2024 })),
    ).toBe("2024");
    expect(
      yearsLabel(detail({ kind: "series", year: 2019, continuing: true })),
    ).toBe("2019 – en cours");
    expect(yearsLabel(detail({ year: null }))).toBeNull();
  });

  it("joins year, runtime and rating", () => {
    expect(
      metaParts(detail({ runtimeMinutes: 101, officialRating: "PG-13" })),
    ).toEqual(["2020", "1 h 41", "PG-13"]);
    expect(ratingLabel(6.2)).toBe("6,2");
  });

  it("formats Emby UTC dates as the local day", () => {
    // Emby stores 5 July as 4 July 22:00 UTC for a Paris server; tests run in local time.
    const label = formatDate("2024-07-04T22:00:00.0000000Z");
    expect(label).toMatch(/juillet 2024$/);
    expect(formatDate("pas une date")).toBeNull();
  });
});

describe("technical info", () => {
  it("describes video without claiming playback support", () => {
    expect(
      videoLabel({
        codec: "hevc",
        width: 1920,
        height: 1038,
        bitDepth: 10,
        range: "HDR 10",
      }),
    ).toBe("1080p · HEVC 10 bits · HDR 10");
    expect(
      videoLabel({
        codec: "h264",
        width: 1920,
        height: 800,
        bitDepth: 8,
        range: "SDR",
      }),
    ).toBe("1080p · H.264");
    expect(
      videoLabel({
        codec: "mpeg4",
        width: 720,
        height: 404,
        bitDepth: null,
        range: null,
      }),
    ).toBe("404p · MPEG-4");
  });

  it("labels audio and subtitle tracks", () => {
    expect(
      audioLabel(stream({ language: "fre", codec: "ac3", channels: 6 })),
    ).toBe("Français · Dolby Digital 5.1");
    expect(
      audioLabel(stream({ language: "und", codec: "aac", channels: 2 })),
    ).toBe("Langue inconnue · AAC stéréo");
    expect(
      subtitleLabel(
        stream({
          language: "eng",
          codec: "PGSSUB",
          isForced: true,
          isExternal: true,
        }),
      ),
    ).toBe("Anglais · PGS · forcés · externe");
  });

  it("drops a title that only repeats the forced flag", () => {
    expect(
      subtitleLabel(
        stream({
          language: "fre",
          codec: "subrip",
          title: "Forced",
          isForced: true,
        }),
      ),
    ).toBe("Français · SRT · forcés");
    expect(
      subtitleLabel(
        stream({ language: "fre", codec: "subrip", title: "Full" }),
      ),
    ).toBe("Français · SRT · Full");
  });

  it("formats sizes and bitrates", () => {
    expect(formatSize(3760231036)).toBe("3,5 Go");
    expect(formatSize(null)).toBeNull();
    expect(formatBitrate(4928712)).toBe("4,9 Mb/s");
    expect(formatBitrate(320000)).toBe("320 kb/s");
  });
});

describe("series", () => {
  it("numbers episodes, double episodes and in-season specials", () => {
    expect(episodeNumber(episode({ index: 4 }))).toBe("Épisode 4");
    expect(episodeNumber(episode({ index: 2, indexEnd: 3 }))).toBe(
      "Épisodes 2–3",
    );
    expect(episodeNumber(episode({ seasonIndex: 0, index: 1 }))).toBe(
      "Spécial 1",
    );
    expect(episodeNumber(episode({ index: null }))).toBeNull();
  });

  it("opens the first regular season with something left to watch", () => {
    const seasons = [
      season(0, { unplayedCount: 3 }),
      season(1, { played: true }),
      season(2, { unplayedCount: 4 }),
      season(3, { unplayedCount: 12 }),
    ];
    expect(defaultSeason(seasons)?.index).toBe(2);
    expect(defaultSeason([season(0), season(1, { played: true })])?.index).toBe(
      1,
    );
    expect(defaultSeason([season(0)])?.index).toBe(0);
    expect(defaultSeason([])).toBeNull();
  });

  it("states the resume point", () => {
    expect(resumeLabel({ positionSeconds: 1831, fraction: 0.3 })).toBe(
      "Reprise à 30:31",
    );
  });
});
