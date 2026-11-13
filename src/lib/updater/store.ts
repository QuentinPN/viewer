import { type Readable, writable } from "svelte/store";
import type { UpdateInfo, UpdaterApi } from "./api";

export interface UpdateState {
  ready: UpdateInfo | null;
  installing: boolean;
  /** Last install failure, in French. */
  error: string | null;
}

export interface UpdateStore extends Readable<UpdateState> {
  /** Listens for the event, then asks for an update that was ready before. */
  start: () => Promise<void>;
  install: () => Promise<void>;
  dispose: () => void;
}

const INSTALL_FAILED =
  "L'installation n'a pas pu démarrer. Réessaie plus tard.";

export function createUpdateStore(api: UpdaterApi): UpdateStore {
  const state = writable<UpdateState>({
    ready: null,
    installing: false,
    error: null,
  });
  let stop: (() => void) | null = null;
  let disposed = false;

  const setReady = (info: UpdateInfo) =>
    state.update((s) => ({ ...s, ready: info, error: null }));

  return {
    subscribe: state.subscribe,
    async start() {
      const unlisten = await api.onReady(setReady).catch(() => null);
      if (disposed) {
        unlisten?.();
        return;
      }
      stop = unlisten;
      // The event may have fired before the UI listened.
      const pending = await api.pending().catch(() => null);
      if (pending && !disposed) {
        state.update((s) => (s.ready ? s : { ...s, ready: pending }));
      }
    },
    async install() {
      let busy = false;
      state.update((s) => {
        busy = s.installing || s.ready === null;
        return busy ? s : { ...s, installing: true, error: null };
      });
      if (busy) return;
      try {
        // On success the process exits; returning means it did not.
        await api.install();
        state.update((s) => ({ ...s, installing: false }));
      } catch {
        state.update((s) => ({
          ...s,
          installing: false,
          error: INSTALL_FAILED,
        }));
      }
    },
    dispose() {
      disposed = true;
      stop?.();
      stop = null;
    },
  };
}
