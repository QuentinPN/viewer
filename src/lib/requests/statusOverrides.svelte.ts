// Status of a title as last read on its page: the cards of Découvrir, Parcourir
// and the searches loaded before keep showing it after a request (ADR 0125).
import { SvelteMap } from "svelte/reactivity";
import { mediaKey } from "./browse";
import type { MediaStatus, MediaSummary } from "./types";

interface Override {
  status: MediaStatus | null;
  progress: number | null;
}

const overrides = new SvelteMap<string, Override>();

/** Records the status of a title freshly read from the server. */
export function rememberStatus(m: MediaSummary): void {
  overrides.set(mediaKey(m), {
    status: m.status ?? null,
    progress: m.progress ?? null,
  });
}

/** The status to show on a card: the latest known one, else the card's own. */
export function currentStatus(m: MediaSummary): Override {
  return (
    overrides.get(mediaKey(m)) ?? {
      status: m.status ?? null,
      progress: m.progress ?? null,
    }
  );
}
