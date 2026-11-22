// Connection states of the app. Pure: no timer, no I/O, so every
// transition is covered by Vitest. The coordinator feeds it events.
import type {
  EmbyError,
  SavedServer,
  SessionStatus,
  SignedInUser,
} from "../emby/types";

/**
 * The signed-in app behind the overlays. It stays mounted (hidden) while the
 * server is unreachable or asks for the password, so view, selection and scroll
 * survive; `refresh` is bumped when the connection comes back.
 */
export interface AppSession {
  user: SignedInUser;
  refresh: number;
}

export type ConnectionState =
  /** Branded loading screen; never the sign-in form. */
  | { kind: "restoring" }
  | { kind: "authenticated"; app: AppSession }
  | {
      kind: "server_unreachable";
      app: AppSession | null;
      server: SavedServer;
      error: EmbyError;
      /** A check is in flight. */
      checking: boolean;
      /** Next automatic attempt (epoch ms), `null` when none is planned. */
      retryAt: number | null;
      /** Automatic attempts are over: only "Réessayer" or `online` try again. */
      gaveUp: boolean;
    }
  | {
      kind: "authentication_required";
      app: AppSession | null;
      server: SavedServer;
      error: EmbyError;
    }
  | { kind: "server_configuration" }
  | { kind: "signed_out"; notice: EmbyError | null };

export type ConnectionEvent =
  /** Answer of `emby_restore` or `emby_resume_saved`. */
  | { type: "status"; status: SessionStatus }
  /** The check itself failed (e.g. the Windows vault), not the server. */
  | { type: "checkFailed"; error: EmbyError }
  | { type: "checking" }
  | { type: "retryScheduled"; at: number }
  | { type: "retriesExhausted" }
  /** A view got a 401: the server refused the token. */
  | { type: "sessionRejected"; error: EmbyError }
  | { type: "signedIn"; user: SignedInUser }
  | { type: "signedOut" }
  | { type: "changeServer" }
  /** Resuming a set-aside session: same screen as at startup. */
  | { type: "restoring" };

/** Automatic attempts after a failure; then the screen waits for the user. */
export const MAX_AUTO_RETRIES = 6;
const BACKOFF_SECONDS = [2, 4, 8, 16, 30, 30];

/**
 * Delay before automatic attempt `attempt` (1-based): exponential up to 30 s,
 * with a jitter of 50 to 100 % so clients do not hit a rebooting server together.
 */
export function backoffDelay(attempt: number, random: number): number {
  const index = Math.min(Math.max(attempt, 1), BACKOFF_SECONDS.length) - 1;
  const base = (BACKOFF_SECONDS[index] ?? 30) * 1000;
  const r = Math.min(Math.max(random, 0), 1);
  return Math.round(base * (0.5 + 0.5 * r));
}

export function sameAccount(a: SignedInUser, b: SignedInUser): boolean {
  return a.server.id === b.server.id && a.userId === b.userId;
}

export function savedServerOf(user: SignedInUser): SavedServer {
  return {
    url: user.server.url,
    id: user.server.id,
    name: user.server.name,
    version: user.server.version,
    encrypted: user.server.encrypted,
    userId: user.userId,
    userName: user.userName,
  };
}

/** The app mounted behind the current screen, if any. */
export function appOf(state: ConnectionState): AppSession | null {
  switch (state.kind) {
    case "authenticated":
      return state.app;
    case "server_unreachable":
    case "authentication_required":
      return state.app;
    default:
      return null;
  }
}

/** Same account again: keep the mounted app and ask its views to reload. */
function reopen(previous: AppSession | null, user: SignedInUser): AppSession {
  if (previous && sameAccount(previous.user, user)) {
    return { user, refresh: previous.refresh + 1 };
  }
  return { user, refresh: 0 };
}

export function transition(
  state: ConnectionState,
  event: ConnectionEvent,
): ConnectionState {
  switch (event.type) {
    case "status":
      return fromStatus(state, event.status);
    case "checkFailed":
      if (state.kind === "restoring") {
        return { kind: "signed_out", notice: event.error };
      }
      if (state.kind === "server_unreachable") {
        return { ...state, checking: false, error: event.error };
      }
      return state;
    case "checking":
      return state.kind === "server_unreachable"
        ? { ...state, checking: true, retryAt: null }
        : state;
    case "retryScheduled":
      return state.kind === "server_unreachable"
        ? { ...state, retryAt: event.at, gaveUp: false }
        : state;
    case "retriesExhausted":
      return state.kind === "server_unreachable"
        ? { ...state, retryAt: null, gaveUp: true }
        : state;
    case "sessionRejected": {
      // Concurrent 401s: only the first one changes the screen.
      if (
        state.kind !== "authenticated" &&
        state.kind !== "server_unreachable"
      ) {
        return state;
      }
      const app = appOf(state);
      if (!app) return state;
      return {
        kind: "authentication_required",
        app,
        server: savedServerOf(app.user),
        error: event.error,
      };
    }
    case "signedIn":
      return { kind: "authenticated", app: reopen(appOf(state), event.user) };
    case "signedOut":
      return { kind: "signed_out", notice: null };
    case "changeServer":
      return { kind: "server_configuration" };
    case "restoring":
      return { kind: "restoring" };
  }
}

function fromStatus(
  state: ConnectionState,
  status: SessionStatus,
): ConnectionState {
  const app = appOf(state);
  switch (status.state) {
    case "authenticated":
      return { kind: "authenticated", app: reopen(app, status.user) };
    case "serverUnreachable":
      return {
        kind: "server_unreachable",
        app,
        server: status.server,
        error: status.error,
        checking: false,
        retryAt: null,
        gaveUp: state.kind === "server_unreachable" && state.gaveUp,
      };
    case "authenticationRequired":
      return {
        kind: "authentication_required",
        app,
        server: status.server,
        error: status.error,
      };
    case "signedOut":
      return { kind: "signed_out", notice: null };
  }
}
