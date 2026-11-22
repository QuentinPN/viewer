/** Stops listening to an event or a window notification. */
export type Unlisten = () => void;

/**
 * What every native backend (Tauri, Wails) implements. `invoke` rejects with
 * the command's error value itself (e.g. `{ kind, message }`), not a wrapper.
 */
export interface Native {
  invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T>;
  /** The handler receives the event payload, not a backend envelope. */
  listen<T>(event: string, handler: (payload: T) => void): Promise<Unlisten>;
  window: {
    minimize(): Promise<void>;
    toggleMaximize(): Promise<void>;
    close(): Promise<void>;
    isMaximized(): Promise<boolean>;
    onResized(handler: () => void): Promise<Unlisten>;
  };
}
