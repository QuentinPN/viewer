import type { Native, Unlisten } from "./types";

/** The part of the Wails v3 runtime (`/wails/runtime.js`) used here. */
export interface WailsRuntime {
  Call: { ByName(name: string, ...args: unknown[]): Promise<unknown> };
  Events: {
    On(name: string, callback: (event: { data: unknown }) => void): () => void;
  };
  Window: {
    Minimise(): Promise<void>;
    ToggleMaximise(): Promise<void>;
    Close(): Promise<void>;
    IsMaximised(): Promise<boolean>;
  };
}

/** The Go method every command goes through (go-app/internal/bridge). */
export const INVOKE_METHOD = "viewer/internal/bridge.Bridge.Invoke";

/** Wails' window event for a client-area resize, maximize included. */
export const RESIZED_EVENT = "common:WindowDidResize";

/**
 * The value `invoke` rejects with: a Go command error reaches JS as a
 * `RuntimeError` whose `cause` is the error's JSON (`{ kind, message }`, a
 * bare string or null, see bridge.MarshalError), and that value is what
 * callers get. Anything else (bad arguments, transport, a Go panic without
 * cause) becomes its message string.
 */
export function toRejection(err: unknown): unknown {
  if (err instanceof Error) {
    if (err.name === "RuntimeError" && err.cause !== undefined) {
      return err.cause;
    }
    return err.message;
  }
  return String(err);
}

/** Builds the Wails backend on top of a runtime loader (injected in tests). */
export function createWailsNative(load: () => Promise<WailsRuntime>): Native {
  return {
    invoke: async <T>(cmd: string, args?: Record<string, unknown>) => {
      const rt = await load();
      try {
        return (await rt.Call.ByName(INVOKE_METHOD, cmd, args ?? {})) as T;
      } catch (err) {
        throw toRejection(err);
      }
    },
    listen: async <T>(event: string, handler: (payload: T) => void) => {
      const rt = await load();
      const off: Unlisten = rt.Events.On(event, (e) => handler(e.data as T));
      return off;
    },
    window: {
      minimize: async () => (await load()).Window.Minimise(),
      toggleMaximize: async () => (await load()).Window.ToggleMaximise(),
      close: async () => (await load()).Window.Close(),
      isMaximized: async () => (await load()).Window.IsMaximised(),
      onResized: async (handler: () => void) => {
        const rt = await load();
        const off: Unlisten = rt.Events.On(RESIZED_EVENT, () => handler());
        return off;
      },
    },
  };
}

// Served by the Wails asset server, not bundled: a variable keeps Vite from
// resolving it, and the import only happens on the first native call.
const RUNTIME_URL = "/wails/runtime.js";
let runtime: Promise<WailsRuntime> | undefined;
const loadRuntime = (): Promise<WailsRuntime> => {
  runtime ??= import(/* @vite-ignore */ RUNTIME_URL) as Promise<WailsRuntime>;
  return runtime;
};

// Built on first use: nothing runs at module load (unit tests import this
// module without a Wails runtime).
let native: Native | undefined;
const get = (): Native => {
  native ??= createWailsNative(loadRuntime);
  return native;
};
export const invoke: Native["invoke"] = <T>(
  cmd: string,
  args?: Record<string, unknown>,
) => get().invoke<T>(cmd, args);
export const listen: Native["listen"] = <T>(
  event: string,
  handler: (payload: T) => void,
) => get().listen<T>(event, handler);
export const window: Native["window"] = {
  minimize: () => get().window.minimize(),
  toggleMaximize: () => get().window.toggleMaximize(),
  close: () => get().window.close(),
  isMaximized: () => get().window.isMaximized(),
  onResized: (handler) => get().window.onResized(handler),
};
