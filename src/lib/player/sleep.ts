// « Minuterie » of the player's options, as on the TV. Times are
// passed in: milliseconds of the wall clock, positions in seconds.
import type { Segment } from "../emby/types";

/** Minutes offered, after « Désactivée ». */
export const SLEEP_MINUTES = [15, 30, 45, 60, 90] as const;

/** Under this much of the file left, « Fin de… » without credits pauses. */
export const END_OF_ITEM_MARGIN_SECONDS = 1;

const MINUTE_MS = 60_000;

export type SleepItem = "episode" | "movie";

export type SleepChoice =
  | { kind: "off" }
  | { kind: "minutes"; minutes: number }
  | { kind: "endOfItem" };

/**
 * The timer of one player session. `deadline` is when minutes run out; it keeps
 * running while paused, as a TV's. `from` is the position « Fin de… » was set
 * at: credits already started then do not count, the end of the file does.
 */
export interface SleepTimer {
  choice: SleepChoice;
  deadline: number;
  from: number;
}

export const SLEEP_OFF: SleepTimer = {
  choice: { kind: "off" },
  deadline: 0,
  from: 0,
};

export const SLEEP_NOTICE = "Minuterie écoulée : lecture en pause";

const endName = (item: SleepItem) =>
  item === "episode" ? "Fin de l'épisode" : "Fin du film";

/** The choices in order; « Fin de… » last. */
export function sleepChoices(): SleepChoice[] {
  return [
    { kind: "off" },
    ...SLEEP_MINUTES.map((minutes) => ({ kind: "minutes" as const, minutes })),
    { kind: "endOfItem" },
  ];
}

export function sameChoice(a: SleepChoice, b: SleepChoice): boolean {
  if (a.kind === "minutes" && b.kind === "minutes")
    return a.minutes === b.minutes;
  return a.kind === b.kind;
}

/** The timer for `choice`, set now. */
export function sleepTimer(
  choice: SleepChoice,
  nowMs: number,
  position: number,
): SleepTimer {
  switch (choice.kind) {
    case "off":
      return SLEEP_OFF;
    case "minutes":
      return { choice, deadline: nowMs + choice.minutes * MINUTE_MS, from: 0 };
    case "endOfItem":
      return { choice, deadline: 0, from: position };
  }
}

/** « Fin de… » set: the up-next card and the next episode must not come. */
export const holdsNext = (timer: SleepTimer) =>
  timer.choice.kind === "endOfItem";

/**
 * Where « Fin de… » pauses: the start of the first credits after `from`, else
 * just before the end. Null while neither is known.
 */
export function sleepStop(
  segments: readonly Segment[],
  duration: number,
  from: number,
): number | null {
  const credits = segments
    .filter((s) => s.kind === "credits" && s.start > from)
    .map((s) => s.start);
  if (credits.length > 0) return Math.min(...credits);
  return duration > 0
    ? Math.max(0, duration - END_OF_ITEM_MARGIN_SECONDS)
    : null;
}

/** Whether the timer ran out at `nowMs`, or « Fin de… » reached its stop. */
export function sleepDue(
  timer: SleepTimer,
  nowMs: number,
  position: number,
  duration: number,
  segments: readonly Segment[],
): boolean {
  switch (timer.choice.kind) {
    case "off":
      return false;
    case "minutes":
      return nowMs >= timer.deadline;
    case "endOfItem": {
      const stop = sleepStop(segments, duration, timer.from);
      return stop !== null && position >= stop;
    }
  }
}

/** Whole minutes left, rounded up; null unless minutes are set. */
export function sleepMinutesLeft(
  timer: SleepTimer,
  nowMs: number,
): number | null {
  if (timer.choice.kind !== "minutes") return null;
  return Math.ceil(Math.max(0, timer.deadline - nowMs) / MINUTE_MS);
}

/** The option's label. */
export function sleepValue(choice: SleepChoice, item: SleepItem): string {
  switch (choice.kind) {
    case "off":
      return "Désactivée";
    case "minutes":
      return `${choice.minutes} min`;
    case "endOfItem":
      return endName(item);
  }
}

/** Shown in the controls while the timer is set; null when it is not. */
export function sleepBadge(
  timer: SleepTimer,
  nowMs: number,
  item: SleepItem,
): string | null {
  switch (timer.choice.kind) {
    case "off":
      return null;
    case "minutes":
      return `Arrêt dans ${sleepMinutesLeft(timer, nowMs)} min`;
    case "endOfItem":
      return `Arrêt : ${endName(item).replace("F", "f")}`;
  }
}
