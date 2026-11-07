// Version chooser of a film or episode with several media sources (ADR 0036): pure
// labels and the per-item memory; unit tested in versions.test.ts.
import type { MediaInfo } from "../emby/types";
import {
  codecName,
  formatBitrate,
  formatSize,
  resolutionLabel,
} from "./format";

export interface VersionChoice {
  /** `MediaSourceId` passed to `emby_play`. */
  id: string;
  label: string;
}

/** `4K · HEVC 10 bits · HDR 10 · 27,9 Go`: size, else bitrate; SDR is not mentioned. */
export function versionDetails(m: MediaInfo): string {
  const v = m.video;
  const depth = v?.bitDepth && v.bitDepth > 8 ? `${v.bitDepth} bits` : null;
  return [
    v ? resolutionLabel(v) : null,
    [codecName(v?.codec ?? null), depth].filter(Boolean).join(" "),
    v?.range && v.range !== "SDR" ? v.range : null,
    formatSize(m.sizeBytes) ?? formatBitrate(m.bitrate),
  ]
    .filter((p): p is string => !!p)
    .join(" · ");
}

/** The server's name first (`2160p — 4K · HEVC…`), else `Version 2 — …`. */
export function versionLabel(m: MediaInfo, index: number): string {
  const name = m.name?.trim() || `Version ${index + 1}`;
  const details = versionDetails(m);
  return details ? `${name} — ${details}` : name;
}

/** One choice per version with an id; none when there is nothing to choose. */
export function versionChoices(media: MediaInfo[]): VersionChoice[] {
  const choices = media.flatMap((m, i) =>
    m.id ? [{ id: m.id, label: versionLabel(m, i) }] : [],
  );
  return choices.length > 1 ? choices : [];
}

/** Per server, user and item; ids only, nothing sensitive. */
export const versionKey = (scope: string, itemId: string): string =>
  `viewer.version.v1:${scope}:${itemId}`;

/**
 * The version remembered for this item, if the server still offers it; otherwise
 * the first, which is the one the server picks when asked for none.
 */
export function loadVersion(
  storage: Pick<Storage, "getItem"> | null,
  key: string,
  choices: VersionChoice[],
): string | null {
  let saved: string | null = null;
  try {
    saved = storage?.getItem(key) ?? null;
  } catch {
    // Storage may be missing or throw (private mode): the default then.
  }
  return choices.find((c) => c.id === saved)?.id ?? choices[0]?.id ?? null;
}

/** The server's default (first version) is not stored: the key is removed instead. */
export function saveVersion(
  storage: Pick<Storage, "setItem" | "removeItem"> | null,
  key: string,
  choices: VersionChoice[],
  id: string,
): void {
  try {
    if (id === choices[0]?.id) storage?.removeItem(key);
    else storage?.setItem(key, id);
  } catch {
    // A display preference: losing it is harmless.
  }
}
