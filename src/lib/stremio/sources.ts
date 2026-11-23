// « Autres sources »: the streams of each addon as they arrive, and their
// French labels. Pure, so the panel only shows what these return.
import { formatSize } from "../detail/format";
import type { DetailKind, PlaybackStart } from "../emby/types";
import type {
  AddonStreams,
  StreamDescriptor,
  StremioSources,
  StremioState,
} from "./types";

/** Shown over the list when a Stremio stream failed to open or broke off. */
export const SOURCE_FAILED =
  "Ce lien ne répond plus, choisis une autre source.";

export type AddonState =
  | { kind: "loading" }
  | { kind: "ok"; streams: StreamDescriptor[] }
  | { kind: "empty" }
  | { kind: "error"; message: string };

export interface AddonSection {
  id: string;
  name: string;
  state: AddonState;
}

export interface SourcesState {
  query: string;
  addons: AddonSection[];
  /** Streams that cannot be played, all addons together. */
  unsupported: number;
}

export function startSources(s: StremioSources): SourcesState {
  return {
    query: s.query,
    addons: (s.addons ?? []).map((a) => ({
      id: a.id,
      name: a.name,
      state: { kind: "loading" },
    })),
    unsupported: 0,
  };
}

/** One addon's state; an answer for another query or addon changes nothing. */
function settle(
  s: SourcesState,
  query: string,
  addonId: string,
  state: AddonState,
  unsupported: number,
): SourcesState {
  if (query !== s.query || !s.addons.some((a) => a.id === addonId)) return s;
  return {
    query: s.query,
    addons: s.addons.map((a) => (a.id === addonId ? { ...a, state } : a)),
    unsupported: s.unsupported + unsupported,
  };
}

export function addonAnswered(
  s: SourcesState,
  query: string,
  addonId: string,
  answer: AddonStreams,
): SourcesState {
  const streams = answer.streams ?? [];
  return settle(
    s,
    query,
    addonId,
    streams.length > 0 ? { kind: "ok", streams } : { kind: "empty" },
    answer.unsupported,
  );
}

/** `message` is Go's, already « Comet : indisponible. ». */
export function addonFailed(
  s: SourcesState,
  query: string,
  addonId: string,
  message: string,
): SourcesState {
  return settle(s, query, addonId, { kind: "error", message }, 0);
}

/** The line under an addon's name; none once its streams are listed. */
export function addonNote(state: AddonState): string | null {
  switch (state.kind) {
    case "loading":
      return "Recherche…";
    case "empty":
      return "Aucun flux.";
    case "error":
      return state.message;
    case "ok":
      return null;
  }
}

function lines(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l !== "");
}

/** The name on one line, the description lines under it, the size aside. */
export function streamLines(s: StreamDescriptor): {
  title: string;
  details: string[];
  size: string | null;
} {
  return {
    title: lines(s.name).join(" · "),
    details: lines(s.description),
    size: formatSize(s.size),
  };
}

export function unsupportedNote(count: number): string | null {
  return count > 0
    ? `${count} flux non pris en charge (torrent sans lien)`
    : null;
}

/**
 * « Autres sources » on a film or an episode once a Stremio account is
 * linked; an expired session still shows it, to say so on click.
 */
export function offersSources(
  state: StremioState | null,
  kind: DetailKind,
): boolean {
  return (
    kind !== "series" && (state === "signed_in" || state === "reauth_required")
  );
}

/** A Stremio stream: no Emby quality, chapters, segments or next episode. */
export function isStremio(p: Pick<PlaybackStart, "origin">): boolean {
  return p.origin === "stremio";
}
