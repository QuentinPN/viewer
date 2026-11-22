// « Qualité » of the player's options: another bitrate cap for this
// playback only, applied by an explicit « Appliquer ». Caps and their validation
// live in Rust; this only orders the choices and words the state.
import { capLabel } from "../settings/quality";

export type QualityPhase =
  | { kind: "idle" }
  | { kind: "applying" }
  | { kind: "failed"; message: string };

export const QUALITY_IDLE: QualityPhase = { kind: "idle" };

/** « Maximale » first, then the caps offered, highest first. */
export function qualityChoices(caps: readonly number[]): (number | null)[] {
  return [null, ...[...caps].sort((a, b) => b - a)];
}

export const qualityLabel = (cap: number | null) => capLabel(cap);

/** « Appliquer » only does something for another cap, and once at a time. */
export function canApply(
  pending: number | null,
  current: number | null,
  phase: QualityPhase,
): boolean {
  return pending !== current && phase.kind !== "applying";
}

/** Line under the choices; null when there is nothing to say. */
export function qualityNote(phase: QualityPhase): string | null {
  switch (phase.kind) {
    case "idle":
      return null;
    case "applying":
      return "Changement de qualité…";
    case "failed":
      return `Qualité inchangée : ${phase.message}`;
  }
}
