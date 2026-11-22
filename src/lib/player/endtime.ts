// The clock and « Fin à » of the player's controls, as on the TV.

const MINUTE_MS = 60_000;

/** « 21:05 »: 24 h, local time of the machine. */
export function formatClock(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * When playback ends if it plays on from `position` at `speed`: what is left of
 * `duration` lasts that much less at a higher speed. Null while the duration is
 * unknown.
 */
export function endsAt(
  nowMs: number,
  position: number,
  duration: number,
  speed = 1,
): number | null {
  if (!Number.isFinite(duration) || duration <= 0) return null;
  const rate = Number.isFinite(speed) && speed > 0 ? speed : 1;
  const left = Math.max(0, duration - Math.max(0, position)) / rate;
  return nowMs + Math.round(left * 1000);
}

/** « Fin à 22:47 », or null without a duration. */
export function endLabel(
  nowMs: number,
  position: number,
  duration: number,
  speed = 1,
): string | null {
  const end = endsAt(nowMs, position, duration, speed);
  return end === null ? null : `Fin à ${formatClock(end)}`;
}

/** Milliseconds until the next minute starts; never zero, so a wait moves on. */
export function untilNextMinute(nowMs: number): number {
  return MINUTE_MS - (((nowMs % MINUTE_MS) + MINUTE_MS) % MINUTE_MS);
}
