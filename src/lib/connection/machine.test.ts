import { describe, expect, it } from "vitest";
import type { EmbyError } from "../emby/types";
import { saved, user } from "./fixtures";
import {
  backoffDelay,
  type ConnectionState,
  MAX_AUTO_RETRIES,
  transition,
} from "./machine";

const unreachable: EmbyError = { kind: "unreachable", message: "injoignable" };
const expired: EmbyError = { kind: "sessionExpired", message: "expirée" };
const authenticated: ConnectionState = {
  kind: "authenticated",
  app: { user, refresh: 0 },
};

describe("backoffDelay", () => {
  it("doubles up to 30 s with 50 to 100 % jitter", () => {
    expect([1, 2, 3, 4, 5, 6, 9].map((a) => backoffDelay(a, 1))).toEqual([
      2000, 4000, 8000, 16000, 30000, 30000, 30000,
    ]);
    expect(backoffDelay(1, 0)).toBe(1000);
    expect(backoffDelay(5, 0.5)).toBe(22500);
    expect(backoffDelay(0, 2)).toBe(2000);
    expect(MAX_AUTO_RETRIES).toBe(6);
  });
});

describe("transition", () => {
  it("restores at startup without passing through the sign-in form", () => {
    const next = transition(
      { kind: "restoring" },
      { type: "status", status: { state: "authenticated", user } },
    );
    expect(next).toEqual({ kind: "authenticated", app: { user, refresh: 0 } });
    expect(
      transition(
        { kind: "restoring" },
        { type: "status", status: { state: "signedOut" } },
      ),
    ).toEqual({ kind: "signed_out", notice: null });
  });

  it("Server offline at startup: recovery screen with no app behind", () => {
    const next = transition(
      { kind: "restoring" },
      {
        type: "status",
        status: {
          state: "serverUnreachable",
          server: saved,
          error: unreachable,
        },
      },
    );
    expect(next).toMatchObject({
      kind: "server_unreachable",
      app: null,
      server: saved,
      checking: false,
      gaveUp: false,
    });
  });

  it("Server offline mid-session keeps the app mounted, refreshes it on return", () => {
    const lost = transition(authenticated, {
      type: "status",
      status: { state: "serverUnreachable", server: saved, error: unreachable },
    });
    expect(lost).toMatchObject({
      kind: "server_unreachable",
      app: { refresh: 0 },
    });
    const checking = transition(lost, { type: "checking" });
    expect(checking).toMatchObject({ checking: true, retryAt: null });
    const back = transition(checking, {
      type: "status",
      status: { state: "authenticated", user },
    });
    expect(back).toEqual({ kind: "authenticated", app: { user, refresh: 1 } });
  });

  it("an invalid token asks for the password with the app kept", () => {
    const next = transition(authenticated, {
      type: "sessionRejected",
      error: expired,
    });
    expect(next).toEqual({
      kind: "authentication_required",
      app: { user, refresh: 0 },
      server: saved,
      error: expired,
    });
    // Reauthenticating as the same user keeps the navigation.
    expect(transition(next, { type: "signedIn", user })).toEqual({
      kind: "authenticated",
      app: { user, refresh: 1 },
    });
    // Another account starts a fresh app.
    const other = { ...user, userId: "u2" };
    expect(transition(next, { type: "signedIn", user: other })).toEqual({
      kind: "authenticated",
      app: { user: other, refresh: 0 },
    });
  });

  it("concurrent 401s produce a single transition", () => {
    const first = transition(authenticated, {
      type: "sessionRejected",
      error: expired,
    });
    const second = transition(first, {
      type: "sessionRejected",
      error: expired,
    });
    expect(second).toBe(first);
    // A 401 with nothing signed in changes nothing either.
    const out: ConnectionState = { kind: "signed_out", notice: null };
    expect(transition(out, { type: "sessionRejected", error: expired })).toBe(
      out,
    );
  });

  it("deleted or disabled account at startup asks for the password", () => {
    const error: EmbyError = { kind: "accountUnavailable", message: "x" };
    expect(
      transition(
        { kind: "restoring" },
        {
          type: "status",
          status: { state: "authenticationRequired", server: saved, error },
        },
      ),
    ).toEqual({
      kind: "authentication_required",
      app: null,
      server: saved,
      error,
    });
  });

  it("retry planning only applies to the recovery screen", () => {
    const lost: ConnectionState = {
      kind: "server_unreachable",
      app: null,
      server: saved,
      error: unreachable,
      checking: false,
      retryAt: null,
      gaveUp: false,
    };
    expect(transition(lost, { type: "retryScheduled", at: 5 })).toMatchObject({
      retryAt: 5,
    });
    const over = transition(lost, { type: "retriesExhausted" });
    expect(over).toMatchObject({ retryAt: null, gaveUp: true });
    // Still unreachable after a manual attempt: stays given up until a success.
    expect(
      transition(transition(over, { type: "checking" }), {
        type: "status",
        status: {
          state: "serverUnreachable",
          server: saved,
          error: unreachable,
        },
      }),
    ).toMatchObject({ gaveUp: true });
    expect(transition(authenticated, { type: "retryScheduled", at: 5 })).toBe(
      authenticated,
    );
  });

  it("a failing check updates the error, or leaves startup for the form", () => {
    const vault: EmbyError = { kind: "credentialStore", message: "coffre" };
    expect(
      transition({ kind: "restoring" }, { type: "checkFailed", error: vault }),
    ).toEqual({ kind: "signed_out", notice: vault });
    expect(
      transition(authenticated, { type: "checkFailed", error: vault }),
    ).toBe(authenticated);
  });

  it("change server, sign out and resume leave the app", () => {
    expect(transition(authenticated, { type: "changeServer" })).toEqual({
      kind: "server_configuration",
    });
    expect(transition(authenticated, { type: "signedOut" })).toEqual({
      kind: "signed_out",
      notice: null,
    });
    expect(
      transition({ kind: "server_configuration" }, { type: "restoring" }),
    ).toEqual({ kind: "restoring" });
  });
});
