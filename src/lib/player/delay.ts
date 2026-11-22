// « Synchronisation » of the player's options: subtitle and audio delays, as on
// the TV. A new file starts from none on both.

/** One press of − or +, or of Z / X. */
export const DELAY_STEP_SECONDS = 0.1;
/** Either way: past it, the file itself is out of sync. */
export const DELAY_MAX_SECONDS = 10;

export type DelayKind = "subtitle" | "audio";
export type DelayChange = "earlier" | "later" | "reset";

export interface Delays {
  subtitle: number;
  audio: number;
}

export const NO_DELAYS: Delays = { subtitle: 0, audio: 0 };

const tenths = (seconds: number) =>
  Number.isFinite(seconds) ? Math.round(seconds * 10) : 0;

/** The delay after `change`, on whole tenths so steps never drift, within ±10 s. */
export function changeDelay(current: number, change: DelayChange): number {
  if (change === "reset") return 0;
  const next = tenths(current) + (change === "later" ? 1 : -1);
  const max = DELAY_MAX_SECONDS * 10;
  return Math.min(max, Math.max(-max, next)) / 10;
}

/** « +0,3 s », « −1,2 s » (real minus sign), « Aucun » once it rounds to zero. */
export function formatDelay(seconds: number): string {
  const t = tenths(seconds);
  if (t === 0) return "Aucun";
  const value = Math.abs(t);
  return `${t > 0 ? "+" : "−"}${Math.floor(value / 10)},${value % 10} s`;
}
