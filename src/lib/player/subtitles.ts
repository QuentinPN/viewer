// Audio and subtitle preferences (ADR 0035): kept on this PC, sent with each
// playback; the native side picks the tracks and styles the subtitles.

export type SubtitleMode = "server" | "off" | "forcedOnly" | "always";
export type SubtitleSize = "small" | "normal" | "large" | "larger";
export type SubtitleColor = "white" | "yellow";
export type SubtitleBackground = "outline" | "box";
export type SubtitlePosition = "bottom" | "raised" | "top";

export interface TrackPreferences {
  /** ISO 639-2/B code of {@link LANGUAGES}; `null` keeps the server's pick. */
  audioLanguage: string | null;
  subtitleLanguage: string | null;
  subtitleMode: SubtitleMode;
}

export interface SubtitleStyle {
  size: SubtitleSize;
  color: SubtitleColor;
  background: SubtitleBackground;
  position: SubtitlePosition;
  /** Also restyle ASS/SSA subtitles, which otherwise keep their own look. */
  overrideAss: boolean;
}

/** What `emby_play` receives as `preferences`. */
export interface PlaybackPreferences {
  tracks: TrackPreferences;
  subtitleStyle: SubtitleStyle;
}

export const SUBTITLES_KEY = "viewer.player.subtitles";

export const DEFAULT_PREFERENCES: PlaybackPreferences = {
  tracks: {
    audioLanguage: null,
    subtitleLanguage: null,
    subtitleMode: "server",
  },
  subtitleStyle: {
    size: "normal",
    color: "white",
    background: "outline",
    position: "bottom",
    overrideAss: false,
  },
};

/** Languages the native side accepts, in the order offered. */
export const LANGUAGES: readonly { code: string; label: string }[] = [
  { code: "fre", label: "Français" },
  { code: "eng", label: "Anglais" },
  { code: "spa", label: "Espagnol" },
  { code: "ger", label: "Allemand" },
  { code: "ita", label: "Italien" },
  { code: "por", label: "Portugais" },
  { code: "dut", label: "Néerlandais" },
  { code: "jpn", label: "Japonais" },
  { code: "kor", label: "Coréen" },
  { code: "chi", label: "Chinois" },
  { code: "rus", label: "Russe" },
  { code: "ara", label: "Arabe" },
];

export const SUBTITLE_MODES: readonly { value: SubtitleMode; label: string }[] =
  [
    { value: "server", label: "Comme le compte Emby" },
    { value: "off", label: "Désactivés" },
    { value: "forcedOnly", label: "Forcés uniquement" },
    { value: "always", label: "Toujours" },
  ];

export const SIZES: readonly { value: SubtitleSize; label: string }[] = [
  { value: "small", label: "Petite" },
  { value: "normal", label: "Normale" },
  { value: "large", label: "Grande" },
  { value: "larger", label: "Très grande" },
];

export const COLORS: readonly { value: SubtitleColor; label: string }[] = [
  { value: "white", label: "Blanc" },
  { value: "yellow", label: "Jaune" },
];

export const BACKGROUNDS: readonly {
  value: SubtitleBackground;
  label: string;
}[] = [
  { value: "outline", label: "Contour" },
  { value: "box", label: "Fond sombre" },
];

export const POSITIONS: readonly { value: SubtitlePosition; label: string }[] =
  [
    { value: "bottom", label: "En bas" },
    { value: "raised", label: "Plus haut" },
    { value: "top", label: "En haut" },
  ];

function oneOf<T extends string>(
  value: unknown,
  options: readonly { value: T }[],
  fallback: T,
): T {
  return options.some((o) => o.value === value) ? (value as T) : fallback;
}

function language(value: unknown): string | null {
  return LANGUAGES.some((l) => l.code === value) ? (value as string) : null;
}

/** Reads the saved preferences; anything missing or malformed gives the default. */
export function readPreferences(storage: Storage | null): PlaybackPreferences {
  const d = DEFAULT_PREFERENCES;
  try {
    const raw = storage?.getItem(SUBTITLES_KEY);
    if (!raw) return structuredClone(d);
    const v = JSON.parse(raw) as {
      tracks?: Partial<Record<keyof TrackPreferences, unknown>>;
      subtitleStyle?: Partial<Record<keyof SubtitleStyle, unknown>>;
    } | null;
    const t = v?.tracks;
    const s = v?.subtitleStyle;
    return {
      tracks: {
        audioLanguage: language(t?.audioLanguage),
        subtitleLanguage: language(t?.subtitleLanguage),
        subtitleMode: oneOf(
          t?.subtitleMode,
          SUBTITLE_MODES,
          d.tracks.subtitleMode,
        ),
      },
      subtitleStyle: {
        size: oneOf(s?.size, SIZES, d.subtitleStyle.size),
        color: oneOf(s?.color, COLORS, d.subtitleStyle.color),
        background: oneOf(
          s?.background,
          BACKGROUNDS,
          d.subtitleStyle.background,
        ),
        position: oneOf(s?.position, POSITIONS, d.subtitleStyle.position),
        overrideAss:
          typeof s?.overrideAss === "boolean"
            ? s.overrideAss
            : d.subtitleStyle.overrideAss,
      },
    };
  } catch {
    return structuredClone(d);
  }
}

export function savePreferences(
  storage: Storage | null,
  preferences: PlaybackPreferences,
) {
  try {
    storage?.setItem(SUBTITLES_KEY, JSON.stringify(preferences));
  } catch {
    // Not kept: the default applies at the next launch.
  }
}

/** `localStorage` may be missing or refused: the defaults then apply. */
export function preferenceStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}
