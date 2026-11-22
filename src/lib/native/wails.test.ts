import { describe, expect, it, vi } from "vitest";
import goBridge from "../../../go-app/internal/bridge/bridge.go?raw";
import {
  createWailsNative,
  INVOKE_METHOD,
  RESIZED_EVENT,
  type WailsRuntime,
} from "./wails";

/** What the Wails runtime throws when a bound method returns an error. */
class RuntimeError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "RuntimeError";
    if (cause !== undefined) this.cause = cause;
  }
}

type Listener = (event: { data: unknown }) => void;

function fakeRuntime() {
  const listeners = new Map<string, Set<Listener>>();
  const rt = {
    Call: { ByName: vi.fn<WailsRuntime["Call"]["ByName"]>() },
    Events: {
      On: vi.fn((name: string, cb: Listener) => {
        const set = listeners.get(name) ?? new Set();
        set.add(cb);
        listeners.set(name, set);
        return () => set.delete(cb);
      }),
    },
    Window: {
      Minimise: vi.fn(async () => {}),
      ToggleMaximise: vi.fn(async () => {}),
      Close: vi.fn(async () => {}),
      IsMaximised: vi.fn(async () => true),
    },
  } satisfies WailsRuntime;
  const emit = (name: string, data: unknown) => {
    for (const cb of listeners.get(name) ?? []) cb({ data });
  };
  return { rt, emit };
}

describe("wails native", () => {
  it("invokes Bridge.Invoke by name with the command and its args", async () => {
    const { rt } = fakeRuntime();
    rt.Call.ByName.mockResolvedValue({ version: "1.5.0" });
    const native = createWailsNative(async () => rt);

    await expect(native.invoke("app_info", { verbose: true })).resolves.toEqual(
      { version: "1.5.0" },
    );
    expect(rt.Call.ByName).toHaveBeenCalledWith(INVOKE_METHOD, "app_info", {
      verbose: true,
    });

    await native.invoke("emby_restore");
    expect(rt.Call.ByName).toHaveBeenLastCalledWith(
      INVOKE_METHOD,
      "emby_restore",
      {},
    );
  });

  it("rejects with the Go error value itself", async () => {
    const { rt } = fakeRuntime();
    const native = createWailsNative(async () => rt);
    const cases: [Error, unknown][] = [
      [
        new RuntimeError("notSignedIn: aucune session ouverte", {
          kind: "notSignedIn",
          message: "aucune session ouverte",
        }),
        { kind: "notSignedIn", message: "aucune session ouverte" },
      ],
      [
        new RuntimeError("message d'erreur", "message d'erreur"),
        "message d'erreur",
      ],
      [new RuntimeError("unit error", null), null],
      // No cause (a Go panic), wrong arguments, transport: the message string.
      [new RuntimeError("panic: boom"), "panic: boom"],
      [
        new TypeError("expects 2 arguments, got 1"),
        "expects 2 arguments, got 1",
      ],
      [new Error("Failed to fetch"), "Failed to fetch"],
    ];
    for (const [thrown, expected] of cases) {
      rt.Call.ByName.mockRejectedValueOnce(thrown);
      await expect(native.invoke("emby_sign_in")).rejects.toEqual(expected);
    }
  });

  it("passes the event payload to the handler and unlistens", async () => {
    const { rt, emit } = fakeRuntime();
    const native = createWailsNative(async () => rt);
    const handler = vi.fn();

    const off = await native.listen<{ state: string }>("player:event", handler);
    emit("player:event", { state: "playing" });
    expect(handler).toHaveBeenCalledWith({ state: "playing" });

    off();
    emit("player:event", { state: "paused" });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it("maps the window operations onto the runtime Window", async () => {
    const { rt, emit } = fakeRuntime();
    const native = createWailsNative(async () => rt);

    await native.window.minimize();
    await native.window.toggleMaximize();
    await native.window.close();
    await expect(native.window.isMaximized()).resolves.toBe(true);
    expect(rt.Window.Minimise).toHaveBeenCalledOnce();
    expect(rt.Window.ToggleMaximise).toHaveBeenCalledOnce();
    expect(rt.Window.Close).toHaveBeenCalledOnce();

    const resized = vi.fn();
    const off = await native.window.onResized(resized);
    emit(RESIZED_EVENT, null);
    off();
    emit(RESIZED_EVENT, null);
    expect(resized).toHaveBeenCalledOnce();
  });

  it("loads the runtime lazily, on the first call", async () => {
    const { rt } = fakeRuntime();
    const load = vi.fn(async () => rt);
    const native = createWailsNative(load);
    expect(load).not.toHaveBeenCalled();
    await native.window.isMaximized();
    expect(load).toHaveBeenCalledOnce();
  });
});

describe("INVOKE_METHOD", () => {
  it("matches bridge.InvokeFQN in Go", () => {
    expect(/const InvokeFQN = "([^"]+)"/.exec(goBridge)?.[1]).toBe(
      INVOKE_METHOD,
    );
  });
});
