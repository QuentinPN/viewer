// Labels of the streaming quality setting. The caps and their
// validation live in Rust (`emby::quality`); this only formats them.

const MBITS = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 1 });

/** Value of the "Maximale" option of a select: no cap. */
export const AUTOMATIC = "auto";

/** `40 Mbit/s`, `1,5 Mbit/s`. */
export function mbits(bitsPerSecond: number): string {
  return `${MBITS.format(bitsPerSecond / 1_000_000)} Mbit/s`;
}

/** Option label: `Maximale (automatique)` for no cap. */
export function capLabel(cap: number | null): string {
  return cap === null ? "Maximale (automatique)" : mbits(cap);
}

/** Select value of a cap, and back; anything not offered reads as no cap. */
export function capValue(cap: number | null): string {
  return cap === null ? AUTOMATIC : String(cap);
}

export function parseCap(
  value: string,
  choices: readonly number[],
): number | null {
  const n = Number(value);
  return choices.includes(n) ? n : null;
}

/** Which of the two caps the current server address uses. */
export function addressNote(localAddress: boolean): string {
  return localAddress
    ? "Ce serveur est joint par une adresse du réseau local : le réglage « Réseau local » s'applique."
    : "Ce serveur est joint par une adresse hors du réseau local : le réglage « Accès distant » s'applique.";
}

/** Player info panel: `4 Mbit/s · accès distant`, `Automatique · réseau local`. */
export function capInEffect(cap: number | null, localAddress: boolean): string {
  const where = localAddress ? "réseau local" : "accès distant";
  return `${cap === null ? "Automatique" : mbits(cap)} · ${where}`;
}
