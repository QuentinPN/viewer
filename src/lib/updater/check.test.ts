import { get } from "svelte/store";
import { describe, expect, it, vi } from "vitest";
import type { CheckOutcome, UpdateInfo, UpdaterApi } from "./api";
import { createCheckStore, failureMessage, statusFromOutcome } from "./check";

const v13: UpdateInfo = { version: "1.3.0", notes: "" };

function fakeApi(outcome: CheckOutcome, pending: UpdateInfo | null = null) {
  const api: UpdaterApi = {
    pending: vi.fn(async () => pending),
    install: vi.fn(async () => {}),
    onReady: vi.fn(async () => () => {}),
    check: vi.fn(async () => outcome),
  };
  return api;
}

describe("statusFromOutcome", () => {
  it("maps every outcome", () => {
    expect(statusFromOutcome({ status: "upToDate" })).toEqual({
      kind: "upToDate",
    });
    expect(statusFromOutcome({ status: "ready", info: v13 })).toEqual({
      kind: "ready",
      info: v13,
      installing: false,
      error: null,
    });
    expect(
      statusFromOutcome({ status: "available", version: "1.3.0" }),
    ).toEqual({
      kind: "available",
      version: "1.3.0",
    });
    expect(
      statusFromOutcome({ status: "failed", category: "network" }),
    ).toEqual({
      kind: "failed",
      message: failureMessage("network"),
    });
  });

  it("says plainly when the server is out of reach", () => {
    expect(failureMessage("network")).toContain("réseau de la maison");
    expect(failureMessage("bogus")).toBe(failureMessage("other"));
  });
});

describe("createCheckStore", () => {
  it("goes through checking to the result", async () => {
    const api = fakeApi({ status: "upToDate" });
    const store = createCheckStore(api);
    const done = store.check();
    expect(get(store)).toEqual({ kind: "checking" });
    await done;
    expect(get(store)).toEqual({ kind: "upToDate" });
  });

  it("does not start a second check while one runs", async () => {
    const api = fakeApi({ status: "upToDate" });
    const store = createCheckStore(api);
    const a = store.check();
    const b = store.check();
    await Promise.all([a, b]);
    expect(api.check).toHaveBeenCalledTimes(1);
  });

  it("shows an update already downloaded in the background", async () => {
    const store = createCheckStore(fakeApi({ status: "upToDate" }, v13));
    await store.start();
    expect(get(store)).toMatchObject({ kind: "ready", info: v13 });
  });

  it("reports a rejected command as a failure", async () => {
    const api = fakeApi({ status: "upToDate" });
    api.check = vi.fn(async () => {
      throw new Error("ipc");
    });
    const store = createCheckStore(api);
    await store.check();
    expect(get(store)).toEqual({
      kind: "failed",
      message: failureMessage("other"),
    });
  });

  it("installs a ready update and reports a failed start", async () => {
    const api = fakeApi({ status: "ready", info: v13 });
    api.install = vi.fn(async () => {
      throw new Error("x");
    });
    const store = createCheckStore(api);
    await store.install();
    expect(api.install).not.toHaveBeenCalled();
    await store.check();
    await store.install();
    expect(api.install).toHaveBeenCalledTimes(1);
    expect(get(store)).toMatchObject({ kind: "ready", installing: false });
    expect((get(store) as { error: string }).error).toContain("installation");
  });
});
