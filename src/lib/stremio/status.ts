// French labels of Réglages › Stremio.
import type { AddonInfo, StremioState, StremioStatus } from "./types";

const STATE_LABELS: Record<StremioState, string> = {
  signed_out: "Déconnecté",
  signed_in: "Connecté",
  reauth_required: "Session Stremio expirée. Reconnecte-toi.",
  unreachable: "Stremio ne répond pas.",
};

export function statusLabel(s: StremioStatus): string {
  return STATE_LABELS[s.state];
}

/** « Comet — Films · Séries »: the addon and the kinds it serves streams for. */
export function addonLabel(a: AddonInfo): string {
  const kinds = [a.movie && "Films", a.series && "Séries"].filter(Boolean);
  return kinds.length ? `${a.name} — ${kinds.join(" · ")}` : a.name;
}
