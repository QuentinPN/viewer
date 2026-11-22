import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { listen as tauriListen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import type { Unlisten } from "./types";

/** The only file allowed to import `@tauri-apps` (see `native.test.ts`). */
export const invoke = <T>(
  cmd: string,
  args?: Record<string, unknown>,
): Promise<T> => tauriInvoke<T>(cmd, args);

/** The handler receives the event payload, not Tauri's `Event` envelope. */
export const listen = <T>(
  event: string,
  handler: (payload: T) => void,
): Promise<Unlisten> => tauriListen<T>(event, (e) => handler(e.payload));

/** The window operations the frontend uses, on the current window. */
export const window = {
  minimize: (): Promise<void> => getCurrentWindow().minimize(),
  toggleMaximize: (): Promise<void> => getCurrentWindow().toggleMaximize(),
  close: (): Promise<void> => getCurrentWindow().close(),
  isMaximized: (): Promise<boolean> => getCurrentWindow().isMaximized(),
  onResized: (handler: () => void): Promise<Unlisten> =>
    getCurrentWindow().onResized(() => handler()),
};
