import type { Track } from "./types";

/** Formats seconds as `m:ss` or `h:mm:ss`. Invalid or negative input gives `0:00`. */
export function formatTime(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0)
    return "0:00";
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

const LANGUAGES: Record<string, string> = {
  fre: "Français",
  fra: "Français",
  fr: "Français",
  eng: "Anglais",
  en: "Anglais",
  jpn: "Japonais",
  ja: "Japonais",
  chi: "Chinois",
  zho: "Chinois",
  zh: "Chinois",
  ger: "Allemand",
  deu: "Allemand",
  spa: "Espagnol",
  ita: "Italien",
  kor: "Coréen",
};

/** Human label for a track selector, in French. */
export function trackLabel(track: Track): string {
  const lang = track.lang
    ? (LANGUAGES[track.lang.toLowerCase()] ?? track.lang)
    : null;
  const parts = [track.title, lang, track.codec?.toUpperCase()].filter(
    (p): p is string => typeof p === "string" && p.length > 0,
  );
  const base = parts.length > 0 ? parts.join(" · ") : `Piste ${track.id}`;
  return track.external ? `${base} (externe)` : base;
}
