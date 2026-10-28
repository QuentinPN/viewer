// Pure French labels for the detail page; unit tested in format.test.ts.
import type {
  Episode,
  EpisodeContext,
  EpisodeLink,
  ItemDetail,
  MediaItem,
  ResumePoint,
  Season,
  StreamInfo,
  VideoInfo,
} from "../emby/types";
import { formatMinutes } from "../format/duration";
import { languageName } from "../format/language";
import { formatTime } from "../player/format";

const decimal = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 1 });
const longDate = new Intl.DateTimeFormat("fr-FR", { dateStyle: "long" });

/** `2020`, `2019–2023`, `2019 – en cours`; series dates come from Emby. */
export function yearsLabel(d: ItemDetail): string | null {
  if (d.year == null) return null;
  if (d.kind !== "series") return String(d.year);
  if (d.continuing) return `${d.year} – en cours`;
  if (d.endYear != null && d.endYear !== d.year)
    return `${d.year}–${d.endYear}`;
  return String(d.year);
}

/** `Saison 2 · Épisode 14`, `Saison 1 · Épisodes 2–3`, `Spécial 3` (ADR 0016). */
export function episodePosition(ep: EpisodeContext): string | null {
  if (ep.seasonIndex === 0)
    return ep.index == null ? "Spécial" : `Spécial ${ep.index}`;
  const episode =
    ep.index == null
      ? null
      : ep.indexEnd != null && ep.indexEnd > ep.index
        ? `Épisodes ${ep.index}–${ep.indexEnd}`
        : `Épisode ${ep.index}`;
  return (
    [ep.seasonIndex == null ? null : `Saison ${ep.seasonIndex}`, episode]
      .filter(Boolean)
      .join(" · ") || null
  );
}

/** `S2 · É1`, `S1 · É2–3`, `Spécial 3` for the previous/next buttons. */
export function episodeLinkCode(link: EpisodeLink): string | null {
  if (link.seasonIndex === 0)
    return link.index == null ? "Spécial" : `Spécial ${link.index}`;
  const index =
    link.index == null
      ? null
      : link.indexEnd != null && link.indexEnd > link.index
        ? `É${link.index}–${link.indexEnd}`
        : `É${link.index}`;
  return (
    [link.seasonIndex == null ? null : `S${link.seasonIndex}`, index]
      .filter(Boolean)
      .join(" · ") || null
  );
}

/** Card shown at once while an episode page loads. */
export function episodeItem(e: {
  id: string;
  name: string;
  runtimeMinutes?: number | null;
  played?: boolean;
}): MediaItem {
  return {
    id: e.id,
    name: e.name,
    itemType: "Episode",
    year: null,
    runtimeMinutes: e.runtimeMinutes ?? null,
    poster: null,
    aspectRatio: null,
    played: e.played ?? false,
    unplayedCount: null,
  };
}

/** Year (air date for an episode), duration and age rating of the header line. */
export function metaParts(d: ItemDetail): string[] {
  return [
    d.kind === "episode" ? formatDate(d.premiereDate) : yearsLabel(d),
    formatMinutes(d.runtimeMinutes),
    d.officialRating,
  ].filter((p): p is string => !!p);
}

/** `6,2` for a community rating out of 10. */
export function ratingLabel(rating: number | null): string | null {
  return rating == null ? null : decimal.format(rating);
}

/** Emby dates are UTC instants of a local day: `2024-07-04T22:00Z` is 5 July in France. */
export function formatDate(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : longDate.format(date);
}

/** `3,5 Go`, binary units like Windows Explorer. */
export function formatSize(bytes: number | null): string | null {
  if (bytes == null || bytes <= 0) return null;
  const units = ["o", "Ko", "Mo", "Go", "To"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${decimal.format(value)} ${units[unit]}`;
}

/** `4,9 Mb/s`. */
export function formatBitrate(bps: number | null): string | null {
  if (bps == null || bps <= 0) return null;
  return bps >= 1_000_000
    ? `${decimal.format(bps / 1_000_000)} Mb/s`
    : `${Math.round(bps / 1000)} kb/s`;
}

const CODECS: Record<string, string> = {
  h264: "H.264",
  hevc: "HEVC",
  h265: "HEVC",
  av1: "AV1",
  vp9: "VP9",
  mpeg2video: "MPEG-2",
  mpeg4: "MPEG-4",
  vc1: "VC-1",
  ac3: "Dolby Digital",
  eac3: "Dolby Digital Plus",
  truehd: "Dolby TrueHD",
  dts: "DTS",
  aac: "AAC",
  flac: "FLAC",
  opus: "Opus",
  mp3: "MP3",
  vorbis: "Vorbis",
  subrip: "SRT",
  srt: "SRT",
  ass: "ASS",
  ssa: "SSA",
  pgssub: "PGS",
  dvdsub: "VobSub",
  mov_text: "Texte",
  webvtt: "WebVTT",
};

export function codecName(codec: string | null): string | null {
  if (!codec) return null;
  return CODECS[codec.toLowerCase()] ?? codec.toUpperCase();
}

/** Usual name from the width: Emby files are often letterboxed (1920×800 is 1080p). */
export function resolutionLabel(video: VideoInfo): string | null {
  const w = video.width ?? 0;
  if (w >= 3800) return "4K";
  if (w >= 2500) return "1440p";
  if (w >= 1900) return "1080p";
  if (w >= 1200) return "720p";
  if (video.height) return `${video.height}p`;
  return null;
}

/** `1080p · HEVC 10 bits · HDR 10`; SDR is not mentioned. */
export function videoLabel(video: VideoInfo): string {
  const codec = codecName(video.codec);
  const depth =
    video.bitDepth && video.bitDepth > 8 ? `${video.bitDepth} bits` : null;
  const range = video.range && video.range !== "SDR" ? video.range : null;
  return [
    resolutionLabel(video),
    [codec, depth].filter(Boolean).join(" "),
    range,
  ]
    .filter((p): p is string => !!p)
    .join(" · ");
}

export function channelsLabel(channels: number | null): string | null {
  switch (channels) {
    case null:
    case 0:
      return null;
    case 1:
      return "mono";
    case 2:
      return "stéréo";
    case 6:
      return "5.1";
    case 8:
      return "7.1";
    default:
      return `${channels} canaux`;
  }
}

function streamLanguage(s: StreamInfo): string {
  return languageName(s.language) ?? "Langue inconnue";
}

/** Track title, unless it only repeats the forced flag (`Forced`, `Forcés`). */
function streamTitle(s: StreamInfo): string | null {
  if (!s.title || (s.isForced && /^forc(ed|é|e)s?$/i.test(s.title.trim())))
    return null;
  return s.title;
}

/** `Français · Dolby Digital 5.1`. */
export function audioLabel(s: StreamInfo): string {
  const format = [codecName(s.codec), channelsLabel(s.channels)]
    .filter(Boolean)
    .join(" ");
  return [streamLanguage(s), format, streamTitle(s)]
    .filter(Boolean)
    .join(" · ");
}

/** `Français · SRT · forcés · externe`. */
export function subtitleLabel(s: StreamInfo): string {
  return [
    streamLanguage(s),
    codecName(s.codec),
    streamTitle(s),
    s.isForced ? "forcés" : null,
    s.isExternal ? "externe" : null,
  ]
    .filter(Boolean)
    .join(" · ");
}

/** `Reprise à 30:31`. */
export function resumeLabel(resume: ResumePoint): string {
  return `Reprise à ${formatTime(resume.positionSeconds)}`;
}

/** `Épisode 1`, `Épisodes 2–3`, `Spécial 1` (special aired within a season). */
export function episodeNumber(e: Episode): string | null {
  if (e.seasonIndex === 0)
    return e.index == null ? "Spécial" : `Spécial ${e.index}`;
  if (e.index == null) return null;
  if (e.indexEnd != null && e.indexEnd > e.index)
    return `Épisodes ${e.index}–${e.indexEnd}`;
  return `Épisode ${e.index}`;
}

/** First regular season with unwatched episodes, else the first regular season. */
export function defaultSeason(seasons: Season[]): Season | null {
  const regular = seasons.filter((s) => s.index !== 0);
  return (
    regular.find((s) => !s.played && (s.unplayedCount ?? 0) > 0) ??
    regular[0] ??
    seasons[0] ??
    null
  );
}
