import { invoke } from "../native";
import { LANGUAGES, type TrackPreferences } from "../player/subtitles";

// Audio and subtitle languages: by default the Emby account's
// preferences, which the server applies when it picks the default streams of
// PlaybackInfo; a choice made in Réglages overrides them on this PC.

export type AccountSubtitleMode =
  | "Default"
  | "Always"
  | "OnlyForced"
  | "None"
  | "Smart"
  | "HearingImpaired";

export interface AccountTracks {
  audioLanguages: string[];
  playDefaultAudio: boolean;
  subtitleLanguages: string[];
  subtitleMode: AccountSubtitleMode | null;
}

export const accountApi = {
  tracks: () => invoke<AccountTracks>("emby_account_tracks"),
};

export const ACCOUNT_DEFAULT = "Comme le compte Emby";

/** Emby spellings of each offered language: "fra" and "fr" are French too. */
const ALIASES: Record<string, string> = {
  fra: "fre",
  fr: "fre",
  en: "eng",
  es: "spa",
  deu: "ger",
  de: "ger",
  it: "ita",
  pt: "por",
  nld: "dut",
  nl: "dut",
  ja: "jpn",
  ko: "kor",
  zho: "chi",
  zh: "chi",
  ru: "rus",
  ar: "ara",
};

/** "fre" → "français"; a code outside the list is shown as is. */
export function languageName(code: string): string {
  const c = code.toLowerCase();
  const label = LANGUAGES.find((l) => l.code === (ALIASES[c] ?? c))?.label;
  return label ? label.toLowerCase() : code;
}

/** "français, puis anglais". */
function languageList(codes: string[]): string {
  return codes.map(languageName).join(", puis ");
}

const MODE_LABELS: Record<AccountSubtitleMode, string> = {
  Default: "par défaut (forcés, sinon ceux marqués par défaut)",
  Always: "toujours",
  OnlyForced: "forcés uniquement",
  None: "aucun",
  Smart: "intelligent (si l'audio est dans une autre langue)",
  HearingImpaired: "pour malentendants",
};

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** What the account asks for the audio, as shown under the setting. */
export function accountAudioLabel(account: AccountTracks): string {
  const parts: string[] = [];
  if (account.playDefaultAudio) parts.push("piste par défaut du fichier");
  if (account.audioLanguages.length > 0) {
    parts.push(languageList(account.audioLanguages));
  }
  return parts.length > 0
    ? capitalize(parts.join(", sinon "))
    : "Aucune préférence (choix du serveur)";
}

/** What the account asks for subtitles, as shown under the setting. */
export function accountSubtitleLabel(account: AccountTracks): string {
  if (!account.subtitleMode) return "Aucune préférence (choix du serveur)";
  const mode = capitalize(MODE_LABELS[account.subtitleMode]);
  if (
    account.subtitleMode === "None" ||
    account.subtitleLanguages.length === 0
  ) {
    return mode;
  }
  return `${mode}, en ${languageList(account.subtitleLanguages)}`;
}

export type TrackSource = "account" | "local";

/**
 * Which preference picks the tracks of the next playback, audio and subtitles
 * apart: a local choice always wins; otherwise the account's, through the
 * server's pick.
 */
export function trackSources(local: TrackPreferences): {
  audio: TrackSource;
  subtitles: TrackSource;
} {
  return {
    audio: local.audioLanguage === null ? "account" : "local",
    subtitles: local.subtitleMode === "server" ? "account" : "local",
  };
}
