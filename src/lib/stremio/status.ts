// French labels of Réglages › Stremio, and whether its streams can be asked.
import type { AddonInfo, StremioState, StremioStatus } from "./types";

const STATE_LABELS: Record<StremioState, string> = {
  signed_out: "Déconnecté",
  signed_in: "Connecté",
  reauth_required: "Session Stremio expirée. Reconnecte-toi.",
  unreachable: "Stremio ne répond pas.",
};

/**
 * A Stremio account is linked: its sources are offered. An expired session
 * still offers them; the panel then says to sign in again.
 */
export function stremioUsable(state: StremioState | null): boolean {
  return state === "signed_in" || state === "reauth_required";
}

export function statusLabel(s: StremioStatus): string {
  return STATE_LABELS[s.state];
}

/** « Comet — Films · Séries »: the addon and the kinds it serves streams for. */
export function addonLabel(a: AddonInfo): string {
  const kinds = [a.movie && "Films", a.series && "Séries"].filter(Boolean);
  return kinds.length ? `${a.name} — ${kinds.join(" · ")}` : a.name;
}
