// "Passer l'intro" and "Passer le générique".
import type { Segment } from "../emby/types";

/** Credits ending this close to the end of the file run to the end. */
export const END_SLACK_SECONDS = 2;
/** The button is gone this close to a segment's end: skipping would do nothing. */
export const HIDE_BEFORE_END_SECONDS = 0.5;

/**
 * Settings kept on this PC: whether IntroDB and TheIntroDB are asked, and whether
 * segments are skipped without a click.
 */
export interface SkipSetting {
  external: boolean;
  auto: boolean;
}

export const SKIP_KEY = "viewer.player.skip";
export const DEFAULT_SKIP: SkipSetting = { external: true, auto: false };

/** Reads the saved setting; anything missing or malformed gives the default. */
export function readSkip(storage: Storage | null): SkipSetting {
  try {
    const raw = storage?.getItem(SKIP_KEY);
    if (!raw) return { ...DEFAULT_SKIP };
    const value = JSON.parse(raw) as Partial<SkipSetting> | null;
    return {
      external:
        typeof value?.external === "boolean"
          ? value.external
          : DEFAULT_SKIP.external,
      auto: typeof value?.auto === "boolean" ? value.auto : DEFAULT_SKIP.auto,
    };
  } catch {
    return { ...DEFAULT_SKIP };
  }
}

/** Saves the setting; a refused storage only means it is not kept. */
export function saveSkip(storage: Storage | null, setting: SkipSetting) {
  try {
    storage?.setItem(SKIP_KEY, JSON.stringify(setting));
  } catch {
    // Not kept: the default applies at the next launch.
  }
}

/** Where a segment ends; credits with no end, or near it, end with the file. */
export function segmentEnd(segment: Segment, duration: number): number | null {
  if (segment.end == null) return duration > 0 ? duration : null;
  if (duration > 0 && duration - segment.end <= END_SLACK_SECONDS)
    return duration;
  return segment.end;
}

/** Whether skipping these credits reaches the end of the file. */
export function runsToEnd(segment: Segment, duration: number): boolean {
  const end = segmentEnd(segment, duration);
  return (
    segment.kind === "credits" && end != null && duration > 0 && end >= duration
  );
}

/** The segment being played, if its button should show. */
export function activeSegment(
  segments: readonly Segment[],
  position: number,
  duration: number,
): Segment | null {
  for (const segment of segments) {
    const end = segmentEnd(segment, duration);
    if (end == null) continue;
    if (position >= segment.start && position < end - HIDE_BEFORE_END_SECONDS)
      return segment;
  }
  return null;
}

/**
 * What the button does:
 * - `seek`: jump to the segment's end (an intro, or credits before a scene);
 * - `next`: start the next episode (credits to the end of an episode with one).
 */
export type SkipAction =
  | { kind: "seek"; to: number; label: string }
  | { kind: "next"; label: string };

/** Main action of the segment's button. */
export function skipAction(
  segment: Segment,
  duration: number,
  hasNext: boolean,
): SkipAction | null {
  const end = segmentEnd(segment, duration);
  if (end == null) return null;
  if (segment.kind === "intro")
    return { kind: "seek", to: end, label: "Passer l'intro" };
  if (hasNext && runsToEnd(segment, duration))
    return { kind: "next", label: "Épisode suivant" };
  return { kind: "seek", to: end, label: "Passer le générique" };
}

/** Whether credits that end before the file also offer the next episode. */
export function offersNextToo(
  segment: Segment,
  duration: number,
  hasNext: boolean,
): boolean {
  return hasNext && segment.kind === "credits" && !runsToEnd(segment, duration);
}

/** Stable key of a segment, to skip each one once per playback. */
export const segmentKey = (segment: Segment) =>
  `${segment.kind}@${segment.start}`;

/** How long the button shows on its own once its segment starts. */
export const SKIP_SHOWN_MS = 10_000;

/**
 * When the button of the segment being played appeared. `key` is null outside
 * any segment, so leaving one (its end, or a seek before its start) and
 * entering another start a new countdown.
 */
export interface SkipShown {
  key: string | null;
  since: number;
}

export const NO_SKIP_SHOWN: SkipShown = { key: null, since: 0 };

/** The countdown for segment `key` at `now`; unchanged within the same segment. */
export function trackSkipShown(
  prev: SkipShown,
  key: string | null,
  now: number,
): SkipShown {
  return key === prev.key ? prev : { key, since: now };
}

/**
 * Whether the button shows: for `SKIP_SHOWN_MS` after it appeared, then only
 * with the controls, until the segment ends.
 */
export function skipShown(
  shown: SkipShown,
  now: number,
  controls: boolean,
): boolean {
  if (shown.key === null) return false;
  return controls || now - shown.since < SKIP_SHOWN_MS;
}

/** Milliseconds until the button hides on its own; null once it did, or outside a segment. */
export function skipHidesIn(shown: SkipShown, now: number): number | null {
  if (shown.key === null) return null;
  const left = shown.since + SKIP_SHOWN_MS - now;
  return left > 0 ? left : null;
}
