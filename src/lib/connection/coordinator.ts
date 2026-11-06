// Drives the connection machine (ADR 0012): one check in flight at a time,
// answers of a superseded check dropped, automatic attempts with backoff.
import { toEmbyError } from "../emby/api";
import type { EmbyError, SessionStatus, SignedInUser } from "../emby/types";
import {
  backoffDelay,
  type ConnectionEvent,
  type ConnectionState,
  MAX_AUTO_RETRIES,
  transition,
} from "./machine";

export interface CoordinatorDeps {
  restore: () => Promise<SessionStatus>;
  resumeSaved: (serverId: string, userId: string) => Promise<SessionStatus>;
  changeServer: () => Promise<void>;
  onChange: (state: ConnectionState) => void;
  now?: () => number;
  random?: () => number;
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/** Errors of a view that may mean the server went away: worth one check. */
export function isNetworkError(error: EmbyError): boolean {
  return error.kind === "unreachable" || error.kind === "timeout";
}

export class ConnectionCoordinator {
  #state: ConnectionState = { kind: "restoring" };
  /** Bumped by every user action: a check started before is ignored. */
  #generation = 0;
  #inFlight: Promise<void> | null = null;
  #autoRetries = 0;
  #timer: unknown = null;
  #deps: Required<CoordinatorDeps>;

  constructor(deps: CoordinatorDeps) {
    this.#deps = {
      now: () => Date.now(),
      random: () => Math.random(),
      setTimer: (fn, ms) => setTimeout(fn, ms),
      clearTimer: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
      ...deps,
    };
  }

  get state(): ConnectionState {
    return this.#state;
  }

  /** Startup: restore the saved session behind the branded screen. */
  start(): Promise<void> {
    return this.#check();
  }

  /** "Réessayer": now, and the backoff starts over. */
  retryNow(): Promise<void> {
    this.#autoRetries = 0;
    this.#clearTimer();
    return this.#check();
  }

  /** The WebView says the network is back. */
  online(): Promise<void> {
    if (this.#state.kind !== "server_unreachable") return Promise.resolve();
    return this.retryNow();
  }

  /**
   * An error reported by a view. A 401 ends the session at once; a network
   * error triggers one shared check whose answer decides the screen.
   */
  report(error: EmbyError): Promise<void> {
    if (error.kind === "sessionExpired") {
      this.#dispatch({ type: "sessionRejected", error });
      return Promise.resolve();
    }
    return isNetworkError(error) ? this.verify() : Promise.resolve();
  }

  /** Checks the session of the app shown, e.g. after an interrupted playback. */
  verify(): Promise<void> {
    if (this.#state.kind !== "authenticated") return Promise.resolve();
    return this.#check();
  }

  signedIn(user: SignedInUser): void {
    this.#supersede();
    this.#dispatch({ type: "signedIn", user });
  }

  signedOut(): void {
    this.#supersede();
    this.#dispatch({ type: "signedOut" });
  }

  /** Sets the session aside (kept, not revoked) and shows the server form. */
  async changeServer(): Promise<void> {
    this.#supersede();
    await this.#deps.changeServer();
    this.#dispatch({ type: "changeServer" });
  }

  /** "Revenir à …": brings back a set-aside session, then checks it. */
  resumeSaved(serverId: string, userId: string): Promise<void> {
    this.#supersede();
    this.#dispatch({ type: "restoring" });
    return this.#check(() => this.#deps.resumeSaved(serverId, userId));
  }

  dispose(): void {
    this.#supersede();
  }

  #supersede() {
    this.#generation += 1;
    this.#inFlight = null;
    this.#autoRetries = 0;
    this.#clearTimer();
  }

  #check(op: () => Promise<SessionStatus> = this.#deps.restore): Promise<void> {
    if (this.#inFlight) return this.#inFlight;
    const generation = this.#generation;
    this.#dispatch({ type: "checking" });
    const run = (async () => {
      try {
        const status = await op();
        if (generation !== this.#generation) return;
        this.#dispatch({ type: "status", status });
      } catch (e) {
        if (generation !== this.#generation) return;
        const error = toEmbyError(e);
        // Rust superseded it: whatever replaced it reports its own outcome.
        if (error.kind === "cancelled") return;
        this.#dispatch({ type: "checkFailed", error });
      }
      this.#planRetry(generation);
    })().finally(() => {
      if (this.#inFlight === run) this.#inFlight = null;
    });
    this.#inFlight = run;
    return run;
  }

  #planRetry(generation: number) {
    if (generation !== this.#generation) return;
    const state = this.#state;
    if (state.kind !== "server_unreachable") {
      this.#autoRetries = 0;
      return;
    }
    if (this.#autoRetries >= MAX_AUTO_RETRIES) {
      this.#dispatch({ type: "retriesExhausted" });
      return;
    }
    const attempt = this.#autoRetries + 1;
    const delay = backoffDelay(attempt, this.#deps.random());
    this.#clearTimer();
    this.#timer = this.#deps.setTimer(() => {
      this.#timer = null;
      this.#autoRetries = attempt;
      void this.#check();
    }, delay);
    this.#dispatch({ type: "retryScheduled", at: this.#deps.now() + delay });
  }

  #clearTimer() {
    if (this.#timer !== null) this.#deps.clearTimer(this.#timer);
    this.#timer = null;
  }

  #dispatch(event: ConnectionEvent) {
    const next = transition(this.#state, event);
    if (next === this.#state) return;
    this.#state = next;
    this.#deps.onChange(next);
  }
}
