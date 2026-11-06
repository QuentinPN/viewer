import { describe, expect, it, vi } from "vitest";
import type { EmbyError, SessionStatus } from "../emby/types";
import { ConnectionCoordinator, isNetworkError } from "./coordinator";
import { saved, user } from "./fixtures";
import type { ConnectionState } from "./machine";

const unreachable: EmbyError = { kind: "unreachable", message: "injoignable" };
const offline: SessionStatus = {
  state: "serverUnreachable",
  server: saved,
  error: unreachable,
};
const online: SessionStatus = { state: "authenticated", user };

/** A promise the test resolves or rejects when it wants. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function setup(
  answers: Array<SessionStatus | EmbyError | Promise<SessionStatus>>,
) {
  const timers: Array<{ fn: () => void; ms: number; cleared: boolean }> = [];
  const states: ConnectionState[] = [];
  const restore = vi.fn(async () => {
    const next = answers.shift();
    if (!next) throw new Error("unexpected restore");
    if (next instanceof Promise) return next;
    if ("kind" in next) throw next;
    return next;
  });
  const resumeSaved = vi.fn(async () => online);
  const changeServer = vi.fn(async () => {});
  const c = new ConnectionCoordinator({
    restore,
    resumeSaved,
    changeServer,
    onChange: (s) => states.push(s),
    now: () => 1000,
    random: () => 1,
    setTimer: (fn, ms) => {
      const t = { fn, ms, cleared: false };
      timers.push(t);
      return t;
    },
    clearTimer: (h) => {
      (h as { cleared: boolean }).cleared = true;
    },
  });
  /** Fires the last planned timer and waits for its check. */
  const fire = async () => {
    const t = timers.at(-1);
    if (!t || t.cleared) throw new Error("no pending timer");
    t.cleared = true;
    t.fn();
    await flush();
  };
  return { c, restore, resumeSaved, changeServer, timers, states, fire };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe("ConnectionCoordinator", () => {
  it("restarts into the app when the saved session is valid", async () => {
    const { c, states } = setup([online]);
    await c.start();
    expect(c.state).toEqual({
      kind: "authenticated",
      app: { user, refresh: 0 },
    });
    // The sign-in form never appeared on the way.
    expect(states.some((s) => s.kind === "signed_out")).toBe(false);
  });

  it("Server offline at startup: backoff, then recovery when it comes back", async () => {
    const { c, timers, fire } = setup([offline, offline, online]);
    await c.start();
    expect(c.state).toMatchObject({
      kind: "server_unreachable",
      retryAt: 3000,
    });
    expect(timers.map((t) => t.ms)).toEqual([2000]);
    await fire();
    expect(timers.map((t) => t.ms)).toEqual([2000, 4000]);
    await fire();
    expect(c.state.kind).toBe("authenticated");
  });

  it("stops after six automatic attempts, then waits for the user", async () => {
    const { c, timers, fire } = setup(Array(7).fill(offline));
    await c.start();
    for (let i = 0; i < 6; i++) await fire();
    expect(timers.map((t) => t.ms)).toEqual([
      2000, 4000, 8000, 16000, 30000, 30000,
    ]);
    expect(c.state).toMatchObject({
      kind: "server_unreachable",
      gaveUp: true,
      retryAt: null,
    });
  });

  it("manual retry tries at once and restarts the backoff", async () => {
    const { c, timers, restore, fire } = setup([
      offline,
      offline,
      offline,
      online,
    ]);
    await c.start();
    await fire(); // second step planned: 4 s
    expect(timers.at(-1)?.ms).toBe(4000);
    await c.retryNow();
    expect(timers[1]?.cleared).toBe(true);
    expect(timers.at(-1)?.ms).toBe(2000);
    await c.retryNow();
    expect(restore).toHaveBeenCalledTimes(4);
    expect(c.state.kind).toBe("authenticated");
  });

  it("the online event retries only from the recovery screen", async () => {
    const { c, restore } = setup([online, offline, online]);
    await c.start();
    await c.online();
    expect(restore).toHaveBeenCalledTimes(1);
    await c.report(unreachable);
    expect(c.state.kind).toBe("server_unreachable");
    await c.online();
    expect(c.state.kind).toBe("authenticated");
  });

  it("network errors of several views share one check; the app is kept", async () => {
    const pending = deferred<SessionStatus>();
    const { c, restore } = setup([online, pending.promise]);
    await c.start();
    const checks = [
      c.report(unreachable),
      c.report({ kind: "timeout", message: "" }),
    ];
    expect(restore).toHaveBeenCalledTimes(2);
    pending.resolve(offline);
    await Promise.all(checks);
    expect(c.state).toMatchObject({
      kind: "server_unreachable",
      app: { user, refresh: 0 },
    });
  });

  it("an isolated view error that the check does not confirm stays in the view", async () => {
    const { c } = setup([online, online]);
    await c.start();
    await c.report(unreachable);
    expect(c.state).toEqual({
      kind: "authenticated",
      app: { user, refresh: 1 },
    });
    // Other errors (404, 5xx) never trigger a check.
    await c.report({ kind: "notFound", message: "" });
    await c.report({ kind: "server", message: "" });
    expect(c.state).toEqual({
      kind: "authenticated",
      app: { user, refresh: 1 },
    });
  });

  it("concurrent 401s lead to one reauthentication screen", async () => {
    const { c, states } = setup([online]);
    await c.start();
    const expired: EmbyError = { kind: "sessionExpired", message: "" };
    await Promise.all([
      c.report(expired),
      c.report(expired),
      c.report(expired),
    ]);
    expect(
      states.filter((s) => s.kind === "authentication_required"),
    ).toHaveLength(1);
  });

  it("change server cancels a check in flight and its retries", async () => {
    const pending = deferred<SessionStatus>();
    const { c, changeServer, timers } = setup([offline, pending.promise]);
    await c.start();
    const planned = timers[0];
    void c.retryNow();
    await c.changeServer();
    expect(changeServer).toHaveBeenCalledTimes(1);
    expect(planned?.cleared).toBe(true);
    pending.resolve(online);
    await flush();
    expect(c.state).toEqual({ kind: "server_configuration" });
  });

  it("a cancelled answer from Rust is ignored", async () => {
    const { c, states } = setup([{ kind: "cancelled", message: "" }]);
    await c.start();
    expect(c.state).toEqual({ kind: "restoring" });
    expect(states).toEqual([]);
  });

  it("returns to a set-aside server through the restoring screen", async () => {
    const { c, resumeSaved, states } = setup([online]);
    await c.start();
    await c.changeServer();
    await c.resumeSaved("srv1", "u1");
    expect(resumeSaved).toHaveBeenCalledWith("srv1", "u1");
    expect(states.map((s) => s.kind)).toEqual([
      "authenticated",
      "server_configuration",
      "restoring",
      "authenticated",
    ]);
  });

  it("sign-in during a slow startup check wins", async () => {
    const pending = deferred<SessionStatus>();
    const { c } = setup([pending.promise]);
    const start = c.start();
    c.signedIn(user);
    pending.resolve(offline);
    await start;
    expect(c.state.kind).toBe("authenticated");
  });

  it("classifies network errors", () => {
    expect(isNetworkError(unreachable)).toBe(true);
    expect(isNetworkError({ kind: "timeout", message: "" })).toBe(true);
    expect(isNetworkError({ kind: "server", message: "" })).toBe(false);
  });
});
