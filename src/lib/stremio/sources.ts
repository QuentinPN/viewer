// « Autres sources »: the streams of each addon as they arrive, and their
// French labels. Pure, so the panel only shows what these return.
import { formatSize } from "../detail/format";
import type { DetailKind, PlaybackStart } from "../emby/types";
import { formatTime } from "../player/format";
import { stremioUsable } from "./status";
import type {
  AddonStreams,
  ResumePosition,
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

/** The addons with streams: the others are not shown. */
export function shownAddons(s: SourcesState): AddonSection[] {
  return s.addons.filter((a) => a.state.kind === "ok");
}

function count(s: SourcesState, kind: AddonState["kind"]): number {
  return s.addons.filter((a) => a.state.kind === kind).length;
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? one : many;
}

/** « Recherche dans N sources… » while addons are pending, else none. */
export function searchNote(s: SourcesState): string | null {
  const n = count(s, "loading");
  return n > 0
    ? `Recherche dans ${n} ${plural(n, "source", "sources")}…`
    : null;
}

/** When every addon is done and none has streams: nothing found, and why. */
export function outcomeNotes(s: SourcesState): string[] {
  if (s.addons.length === 0 || count(s, "loading") > 0) return [];
  if (shownAddons(s).length > 0) return [];
  const failed = count(s, "error");
  return [
    "Aucune source trouvée.",
    ...(failed > 0
      ? [
          plural(
            failed,
            "1 source n'a pas répondu.",
            `${failed} sources n'ont pas répondu.`,
          ),
        ]
      : []),
  ];
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
  return kind !== "series" && stremioUsable(state);
}

/** A Stremio stream: no Emby quality, chapters, segments or next episode. */
export function isStremio(p: Pick<PlaybackStart, "origin">): boolean {
  return p.origin === "stremio";
}

/** Errors where a new query cannot help: the fix is in Réglages or the title. */
const FINAL = new Set(["signedOut", "reauthRequired", "noImdb", "invalidId"]);

/** Whether « Réessayer » can change the outcome of this error. */
export function canRetry(kind: string): boolean {
  return !FINAL.has(kind);
}

/** Past this share of the runtime, the title counts as watched: no resume. */
const RESUME_LIMIT = 0.95;

/** The second choice of the resume group. */
export const RESTART_LABEL = "Depuis le début";

/** « Reprendre à 42:10 » over the list, or none when there is nothing to resume. */
export function resumeLabel(resume: ResumePosition | null | undefined) {
  if (
    !resume ||
    !Number.isFinite(resume.positionSeconds) ||
    !Number.isFinite(resume.durationSeconds) ||
    resume.positionSeconds <= 0 ||
    resume.positionSeconds >= resume.durationSeconds * RESUME_LIMIT
  )
    return null;
  return `Reprendre à ${formatTime(resume.positionSeconds)}`;
}
