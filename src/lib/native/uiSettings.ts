// UI preferences across the switch from Tauri to Go/Wails. WebView2 keeps
// localStorage per origin: Tauri's `http://tauri.localhost` and Wails'
// `http://wails.localhost` do not share it, though they share the profile
// folder. The Tauri app writes its keys to `ui-settings.json` in the app config
// folder at each launch; the Wails app reads that file back when its own
// storage holds none of the app's keys.
import { backend as nativeBackend, invoke as nativeInvoke } from "./index";

/** The app's localStorage keys that hold a fixed name. */
export const UI_SETTING_KEYS: readonly string[] = [
  "viewer.player.volume", // player/volume.ts VOLUME_KEY
  "viewer.player.skip", // player/segments.ts SKIP_KEY
  "viewer.player.subtitles", // player/subtitles.ts SUBTITLES_KEY
  "viewer.player.nightMode", // player/audiomodes.ts NIGHT_KEY
];

/**
 * The prefixes of the app's per server / user keys. The last one is one key
 * per film with several versions, so it gives way first when the entry cap is
 * reached (see `collectUiSettings`).
 */
export const UI_SETTING_PREFIXES: readonly string[] = [
  "viewer.home-rows.v1:", // home/rows.ts prefsKey
  "viewer.library-options.v1:", // library/options.ts storageKey
  "viewer.search-history.v1:", // search/history.ts historyKey
  "viewer.version.v1:", // detail/versions.ts versionKey
];

/** The limits `ui_settings_export` enforces (Rust and Go): UTF-8 bytes. */
export const UI_SETTINGS_MAX_ENTRIES = 200;
export const UI_SETTINGS_MAX_VALUE_BYTES = 64 * 1024;
/** Keys and values together. */
export const UI_SETTINGS_MAX_TOTAL_BYTES = 512 * 1024;

/** How long the Wails start waits for the import before mounting the app. */
export const UI_SETTINGS_IMPORT_TIMEOUT_MS = 500;

/** Whether `key` is one of the app's UI preferences (the only keys handed over). */
export function isUiSettingKey(key: string): boolean {
  return (
    UI_SETTING_KEYS.includes(key) ||
    UI_SETTING_PREFIXES.some((p) => key.startsWith(p) && key.length > p.length)
  );
}

type UiStorage = Pick<Storage, "length" | "key" | "getItem" | "setItem">;

interface Deps {
  backend: "tauri" | "wails";
  invoke: (cmd: string, args?: Record<string, unknown>) => Promise<unknown>;
  storage: UiStorage | null;
  timeoutMs?: number;
}

const utf8 = new TextEncoder();
const bytes = (s: string): number => utf8.encode(s).length;

/** Lower first: fixed keys, then per user keys in prefix order. */
function rank(key: string): number {
  const i = UI_SETTING_PREFIXES.findIndex((p) => key.startsWith(p));
  return i < 0 ? -1 : i;
}

/**
 * The app's keys in `storage`, within the export limits: a value over the
 * per-value limit is left out, and once the entry or byte budget is spent the
 * remaining keys (the per film versions first) are left out too.
 */
export function collectUiSettings(
  storage: UiStorage | null,
): Record<string, string> {
  const found: [string, string][] = [];
  try {
    if (!storage) return {};
    for (let i = 0; i < storage.length; i++) {
      const key = storage.key(i);
      if (key === null || !isUiSettingKey(key)) continue;
      const value = storage.getItem(key);
      if (value !== null) found.push([key, value]);
    }
  } catch {
    // Storage refused (private mode): nothing to hand over.
    return {};
  }
  found.sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b));
  const entries: Record<string, string> = {};
  let count = 0;
  let total = 0;
  for (const [key, value] of found) {
    if (count >= UI_SETTINGS_MAX_ENTRIES) break;
    const size = bytes(value);
    if (size > UI_SETTINGS_MAX_VALUE_BYTES) continue;
    if (total + bytes(key) + size > UI_SETTINGS_MAX_TOTAL_BYTES) continue;
    entries[key] = value;
    count++;
    total += bytes(key) + size;
  }
  return entries;
}

function hasUiSettings(storage: UiStorage): boolean {
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key !== null && isUiSettingKey(key)) return true;
  }
  return false;
}

/** Writes the allowed keys `storage` does not hold yet. */
function apply(storage: UiStorage, entries: unknown) {
  if (!entries || typeof entries !== "object") return;
  for (const [key, value] of Object.entries(entries)) {
    if (!isUiSettingKey(key) || typeof value !== "string") continue;
    try {
      if (storage.getItem(key) === null) storage.setItem(key, value);
    } catch (e) {
      console.warn("UI setting not restored", key, e);
    }
  }
}

/**
 * Runs once at startup, before the app mounts. Tauri: exports the app's keys
 * without waiting. Wails: when its storage holds none of the app's keys,
 * imports the Tauri export, waiting at most `timeoutMs`; a later answer still
 * fills the keys that are absent. Never rejects: errors are only logged.
 */
export async function handOverUiSettings(deps: Deps): Promise<void> {
  const { storage } = deps;
  if (!storage) return;
  if (deps.backend === "tauri") {
    // Even empty: a key removed since the last launch (a cleared search
    // history) must not come back from an older file.
    const entries = collectUiSettings(storage);
    deps
      .invoke("ui_settings_export", { entries })
      .catch((e) => console.warn("UI settings not exported", e));
    return;
  }
  try {
    if (hasUiSettings(storage)) return;
  } catch {
    return;
  }
  const imported = deps
    .invoke("ui_settings_import")
    .then((entries) => apply(storage, entries))
    .catch((e) => console.warn("UI settings not imported", e));
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<void>((resolve) => {
    timer = setTimeout(
      resolve,
      deps.timeoutMs ?? UI_SETTINGS_IMPORT_TIMEOUT_MS,
    );
  });
  await Promise.race([imported, timeout]);
  clearTimeout(timer);
}

/** `localStorage` may be missing or refused: then nothing is handed over. */
function localStorageOrNull(): UiStorage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** `handOverUiSettings` on this build's backend and the WebView's storage. */
export function startUiSettings(): Promise<void> {
  return handOverUiSettings({
    backend: nativeBackend,
    invoke: nativeInvoke,
    storage: localStorageOrNull(),
  });
}
