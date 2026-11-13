import { type Readable, writable } from "svelte/store";
import type { CheckOutcome, UpdateInfo, UpdaterApi } from "./api";

// Manual check of Réglages › Mises à jour (ADR 0112, manual check addendum).

export type CheckStatus =
  | { kind: "idle" }
  /** One call checks and, in release builds, downloads: both show as one step. */
  | { kind: "checking" }
  | { kind: "upToDate" }
  | {
      kind: "ready";
      info: UpdateInfo;
      installing: boolean;
      error: string | null;
    }
  | { kind: "available"; version: string }
  | { kind: "failed"; message: string };

/** Plain French for an updater failure category (never the URL or raw error). */
export function failureMessage(category: string): string {
  switch (category) {
    case "network":
      return "Le serveur de mises à jour est injoignable. Il n'est accessible que depuis le réseau de la maison : vérifie ta connexion.";
    case "signature":
      return "La mise à jour trouvée n'a pas une signature valide : elle a été refusée.";
    case "manifest":
    case "transport":
      return "Le serveur de mises à jour a répondu de façon inattendue. Réessaie plus tard.";
    case "io":
      return "La mise à jour n'a pas pu être enregistrée sur ce poste.";
    default:
      return "La recherche de mises à jour a échoué. Réessaie plus tard.";
  }
}

export function statusFromOutcome(outcome: CheckOutcome): CheckStatus {
  switch (outcome.status) {
    case "upToDate":
      return { kind: "upToDate" };
    case "ready":
      return {
        kind: "ready",
        info: outcome.info,
        installing: false,
        error: null,
      };
    case "available":
      return { kind: "available", version: outcome.version };
    case "failed":
      return { kind: "failed", message: failureMessage(outcome.category) };
  }
}

const INSTALL_FAILED =
  "L'installation n'a pas pu démarrer. Réessaie plus tard.";

export interface CheckStore extends Readable<CheckStatus> {
  /** Shows an update the background loop already downloaded. */
  start: () => Promise<void>;
  check: () => Promise<void>;
  install: () => Promise<void>;
  dispose: () => void;
}

export function createCheckStore(api: UpdaterApi): CheckStore {
  const state = writable<CheckStatus>({ kind: "idle" });
  let current: CheckStatus = { kind: "idle" };
  const unsubscribe = state.subscribe((s) => {
    current = s;
  });
  let disposed = false;
  const set = (s: CheckStatus) => {
    if (!disposed) state.set(s);
  };

  return {
    subscribe: state.subscribe,
    async start() {
      const pending = await api.pending().catch(() => null);
      if (pending && current.kind === "idle") {
        set({ kind: "ready", info: pending, installing: false, error: null });
      }
    },
    async check() {
      if (current.kind === "checking") return;
      if (current.kind === "ready" && current.installing) return;
      set({ kind: "checking" });
      try {
        set(statusFromOutcome(await api.check()));
      } catch {
        set({ kind: "failed", message: failureMessage("other") });
      }
    },
    async install() {
      const s = current;
      if (s.kind !== "ready" || s.installing) return;
      set({ ...s, installing: true, error: null });
      try {
        // On success the process exits; returning means it did not.
        await api.install();
        set({ ...s, installing: false, error: null });
      } catch {
        set({ ...s, installing: false, error: INSTALL_FAILED });
      }
    },
    dispose() {
      disposed = true;
      unsubscribe();
    },
  };
}
