import { afterEach, describe, expect, it, vi } from "vitest";
import { versionKey } from "../detail/versions";
import { prefsKey } from "../home/rows";
import { storageKey } from "../library/options";
import { NIGHT_KEY } from "../player/audiomodes";
import { SKIP_KEY } from "../player/segments";
import { SUBTITLES_KEY } from "../player/subtitles";
import { VOLUME_KEY } from "../player/volume";
import { historyKey } from "../search/history";
import {
  collectUiSettings,
  handOverUiSettings,
  isUiSettingKey,
  UI_SETTINGS_MAX_ENTRIES,
  UI_SETTINGS_MAX_TOTAL_BYTES,
  UI_SETTINGS_MAX_VALUE_BYTES,
} from "./uiSettings";

function memoryStorage(init: Record<string, string> = {}) {
  const data = new Map(Object.entries(init));
  return {
    data,
    get length() {
      return data.size;
    },
    key: (i: number) => [...data.keys()][i] ?? null,
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
  };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("UI settings keys", () => {
  it("covers every localStorage key the app writes", () => {
    for (const key of [
      VOLUME_KEY,
      SKIP_KEY,
      SUBTITLES_KEY,
      NIGHT_KEY,
      versionKey("srv:user", "item"),
      prefsKey("srv", "user"),
      storageKey("srv", "user", "lib"),
      historyKey("srv", "user"),
    ]) {
      expect(isUiSettingKey(key), key).toBe(true);
    }
  });

  it("refuses other keys and bare prefixes", () => {
    for (const key of [
      "",
      "token",
      "viewer.version.v1:",
      "viewer.player",
      "viewer.player.volumeX",
      "other.viewer.home-rows.v1:a",
    ]) {
      expect(isUiSettingKey(key), key).toBe(false);
    }
  });
});

describe("collectUiSettings", () => {
  it("keeps only the app's keys", () => {
    const s = memoryStorage({
      [VOLUME_KEY]: '{"level":50}',
      [historyKey("s", "u")]: '["dune"]',
      unrelated: "x",
    });
    expect(collectUiSettings(s)).toEqual({
      [VOLUME_KEY]: '{"level":50}',
      [historyKey("s", "u")]: '["dune"]',
    });
  });

  it("drops per film versions first past the entry cap", () => {
    const init: Record<string, string> = {};
    for (let i = 0; i < UI_SETTINGS_MAX_ENTRIES + 20; i++) {
      init[versionKey("s:u", `item${i}`)] = "v";
    }
    init[VOLUME_KEY] = "1";
    init[prefsKey("s", "u")] = "{}";
    const out = collectUiSettings(memoryStorage(init));
    expect(Object.keys(out)).toHaveLength(UI_SETTINGS_MAX_ENTRIES);
    expect(out[VOLUME_KEY]).toBe("1");
    expect(out[prefsKey("s", "u")]).toBe("{}");
  });

  it("leaves out a value over the limit and keeps the total under its limit", () => {
    const big = "é".repeat(UI_SETTINGS_MAX_VALUE_BYTES / 2 + 1); // UTF-8 bytes
    const init: Record<string, string> = { [VOLUME_KEY]: big };
    const chunk = "x".repeat(60 * 1024);
    for (let i = 0; i < 10; i++) init[storageKey("s", "u", `l${i}`)] = chunk;
    const out = collectUiSettings(memoryStorage(init));
    expect(out[VOLUME_KEY]).toBeUndefined();
    const total = Object.entries(out).reduce(
      (n, [k, v]) => n + new TextEncoder().encode(k + v).length,
      0,
    );
    expect(total).toBeLessThanOrEqual(UI_SETTINGS_MAX_TOTAL_BYTES);
    expect(Object.keys(out).length).toBe(8);
  });

  it("gives nothing when storage is missing or refused", () => {
    expect(collectUiSettings(null)).toEqual({});
    const refusing = memoryStorage();
    refusing.key = () => {
      throw new Error("denied");
    };
    Object.defineProperty(refusing, "length", { get: () => 1 });
    expect(collectUiSettings(refusing)).toEqual({});
  });
});

describe("handOverUiSettings on Tauri", () => {
  it("exports the app's keys without waiting", async () => {
    const s = memoryStorage({ [SKIP_KEY]: "{}", other: "x" });
    const invoke = vi.fn(() => new Promise<never>(() => {}));
    await handOverUiSettings({ backend: "tauri", invoke, storage: s });
    expect(invoke).toHaveBeenCalledExactlyOnceWith("ui_settings_export", {
      entries: { [SKIP_KEY]: "{}" },
    });
  });

  it("only logs an export error", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const invoke = vi.fn(() => Promise.reject("réglages refusés"));
    await handOverUiSettings({
      backend: "tauri",
      invoke,
      storage: memoryStorage({ [SKIP_KEY]: "{}" }),
    });
    await flush();
    expect(warn).toHaveBeenCalled();
  });

  it("exports nothing when the app has no key", async () => {
    const invoke = vi.fn();
    await handOverUiSettings({
      backend: "tauri",
      invoke,
      storage: memoryStorage({ other: "x" }),
    });
    expect(invoke).not.toHaveBeenCalled();
  });
});

describe("handOverUiSettings on Wails", () => {
  it("imports into an empty storage, allowed keys only", async () => {
    const s = memoryStorage({ other: "x" });
    const invoke = vi.fn(async () => ({
      [VOLUME_KEY]: "7",
      [historyKey("s", "u")]: "[]",
      token: "secret",
    }));
    await handOverUiSettings({ backend: "wails", invoke, storage: s });
    expect(invoke).toHaveBeenCalledExactlyOnceWith("ui_settings_import");
    expect(Object.fromEntries(s.data)).toEqual({
      other: "x",
      [VOLUME_KEY]: "7",
      [historyKey("s", "u")]: "[]",
    });
  });

  it("does not import when the app already has a key", async () => {
    const invoke = vi.fn();
    await handOverUiSettings({
      backend: "wails",
      invoke,
      storage: memoryStorage({ [NIGHT_KEY]: "1" }),
    });
    expect(invoke).not.toHaveBeenCalled();
  });

  it("accepts no export file and logs an error", async () => {
    const s = memoryStorage();
    await handOverUiSettings({
      backend: "wails",
      invoke: vi.fn(async () => null),
      storage: s,
    });
    expect(s.data.size).toBe(0);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    await handOverUiSettings({
      backend: "wails",
      invoke: vi.fn(() => Promise.reject("fichier illisible")),
      storage: s,
    });
    expect(warn).toHaveBeenCalled();
    expect(s.data.size).toBe(0);
  });

  it("mounts after the timeout, then fills only the keys still absent", async () => {
    vi.useFakeTimers();
    const s = memoryStorage();
    let answer: (v: Record<string, string>) => void = () => {};
    const invoke = vi.fn(
      () =>
        new Promise<Record<string, string>>((r) => {
          answer = r;
        }),
    );
    let done = false;
    const start = handOverUiSettings({
      backend: "wails",
      invoke,
      storage: s,
      timeoutMs: 500,
    }).then(() => {
      done = true;
    });
    await vi.advanceTimersByTimeAsync(499);
    expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await start;
    expect(done).toBe(true);
    // The app saved a choice before the late answer.
    s.setItem(VOLUME_KEY, "mine");
    answer({ [VOLUME_KEY]: "old", [SKIP_KEY]: "{}" });
    await vi.advanceTimersByTimeAsync(0);
    expect(s.getItem(VOLUME_KEY)).toBe("mine");
    expect(s.getItem(SKIP_KEY)).toBe("{}");
  });

  it("does nothing without storage", async () => {
    const invoke = vi.fn();
    await handOverUiSettings({ backend: "wails", invoke, storage: null });
    expect(invoke).not.toHaveBeenCalled();
  });
});
