/**
 * Volume of the player, kept on this PC (ADR 0017): not tied to an account and
 * never sent to Emby. The engine has no mute command: mute plays at 0 and keeps
 * the level to restore.
 */
export interface VolumeSetting {
  /** 0–100. */
  level: number;
  muted: boolean;
}

export const VOLUME_KEY = "viewer.player.volume";
export const DEFAULT_VOLUME: VolumeSetting = { level: 100, muted: false };

const clampLevel = (level: number) =>
  Math.round(Math.min(100, Math.max(0, level)));

/** Reads the saved setting; anything missing or malformed gives the default. */
export function readVolume(storage: Storage | null): VolumeSetting {
  try {
    const raw = storage?.getItem(VOLUME_KEY);
    if (!raw) return { ...DEFAULT_VOLUME };
    const value = JSON.parse(raw) as Partial<VolumeSetting>;
    if (typeof value.level !== "number" || !Number.isFinite(value.level))
      return { ...DEFAULT_VOLUME };
    return { level: clampLevel(value.level), muted: value.muted === true };
  } catch {
    return { ...DEFAULT_VOLUME };
  }
}

/** Saves the setting; a refused storage only means it is not kept. */
export function saveVolume(storage: Storage | null, setting: VolumeSetting) {
  try {
    storage?.setItem(VOLUME_KEY, JSON.stringify(setting));
  } catch {
    // Not kept: the player still works.
  }
}

/** Level sent to the engine. */
export const effectiveVolume = (setting: VolumeSetting) =>
  setting.muted ? 0 : setting.level;

/** Moves the level by `delta`; raising it unmutes, as in most players. */
export function stepVolume(
  setting: VolumeSetting,
  delta: number,
): VolumeSetting {
  const level = clampLevel(setting.level + delta);
  return { level, muted: delta > 0 ? false : setting.muted };
}

/** Sets the level from the slider: 0 is muted, anything else is heard. */
export function setLevel(level: number): VolumeSetting {
  const clamped = clampLevel(level);
  return { level: clamped, muted: clamped === 0 };
}

/** Unmuting at 0 would stay silent: it comes back at a usable level. */
export function toggleMute(setting: VolumeSetting): VolumeSetting {
  if (setting.muted || setting.level === 0)
    return { level: setting.level === 0 ? 50 : setting.level, muted: false };
  return { ...setting, muted: true };
}
