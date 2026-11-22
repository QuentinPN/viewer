// « Vitesse » and « Mode nuit » of the player's options, as on the TV.

/** Speeds offered, slowest first; 1 is « Normale ». */
export const SPEEDS = [0.75, 1, 1.25, 1.5, 2] as const;
export const NORMAL_SPEED = 1;

/** The offered speed nearest `speed`; anything unreadable is « Normale ». */
export function offeredSpeed(speed: number | null | undefined): number {
  if (speed == null || !Number.isFinite(speed) || speed <= 0)
    return NORMAL_SPEED;
  let best: number = NORMAL_SPEED;
  for (const s of SPEEDS)
    if (Math.abs(s - speed) < Math.abs(best - speed)) best = s;
  return best;
}

/** « 0,75× », « 1,5× », « 2× »: up to two decimals, French comma. */
function factor(speed: number): string {
  const text = String(Math.round(speed * 100) / 100).replace(".", ",");
  return `${text}×`;
}

/** The option's label: « Normale » at 1×. */
export function formatSpeed(speed: number): string {
  const s = offeredSpeed(speed);
  return s === NORMAL_SPEED ? "Normale" : factor(s);
}

/** Shown in the controls while the speed is not 1×; null at « Normale ». */
export function speedBadge(speed: number): string | null {
  const s = offeredSpeed(speed);
  return s === NORMAL_SPEED ? null : factor(s);
}

/**
 * Night mode, kept on this PC like the volume: it applies to every playback
 * until turned off, here or in Réglages › Audio et sous-titres.
 */
export const NIGHT_KEY = "viewer.player.nightMode";

export function readNightMode(storage: Storage | null): boolean {
  try {
    return storage?.getItem(NIGHT_KEY) === "1";
  } catch {
    return false;
  }
}

export function saveNightMode(storage: Storage | null, on: boolean) {
  try {
    storage?.setItem(NIGHT_KEY, on ? "1" : "0");
  } catch {
    // Not kept: it still applies to this playback.
  }
}
