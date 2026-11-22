// The logo in use: the raccoon in the outfit of the day (see season.ts), unless
// « Icônes saisonnières » is off. Re-evaluated at startup, at each local
// midnight and when the setting changes. Each change of logo also becomes the icon
// of the window (title bar, taskbar button), on the Wails build only: the pinned
// taskbar icon and the shortcuts stay the exe's resource, the default logo.
import { backend, invoke } from "../native";
import { type SeasonId, seasonForDate } from "./season";

/** localStorage key of the setting: "0" is off, anything else (or nothing) on. */
export const SEASONAL_KEY = "viewer.ui.seasonalLogo";

/** Margin after midnight, so a timer that fires early still lands on the new day. */
const MIDNIGHT_SLACK_MS = 1000;

export interface LogoDeps {
  now: () => Date;
  storage: Storage | null;
  /** Sets the window icon. Rejections are logged, never thrown. */
  applyIcon: (id: SeasonId) => Promise<unknown>;
}

export function readSeasonal(storage: Storage | null): boolean {
  try {
    return storage?.getItem(SEASONAL_KEY) !== "0";
  } catch {
    return true;
  }
}

function saveSeasonal(storage: Storage | null, on: boolean) {
  try {
    storage?.setItem(SEASONAL_KEY, on ? "1" : "0");
  } catch {
    // Not kept: it still applies until the app closes.
  }
}

/** Milliseconds from `now` to the next local midnight (DST days included). */
export function msUntilMidnight(now: Date): number {
  const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  return next.getTime() - now.getTime();
}

export class LogoState {
  /** The logo to show, a key of LOGO_URLS. */
  id = $state<SeasonId>("default");
  /** « Icônes saisonnières ». */
  seasonal = $state(true);

  #deps: LogoDeps;
  #applied: SeasonId | null = null;
  #icon: Promise<unknown> = Promise.resolve();
  #timer: ReturnType<typeof setTimeout> | undefined;

  constructor(deps: LogoDeps) {
    this.#deps = deps;
    this.seasonal = readSeasonal(deps.storage);
    this.id = this.#compute();
  }

  #compute(): SeasonId {
    return this.seasonal ? seasonForDate(this.#deps.now()) : "default";
  }

  /** Picks the logo again and, when it changed (or never was set), the window icon. */
  refresh() {
    this.id = this.#compute();
    if (this.id === this.#applied) return;
    const id = this.id;
    this.#applied = id;
    // One call after the other: the last logo wins even if the native side is slow.
    this.#icon = this.#icon
      .then(() => this.#deps.applyIcon(id))
      .catch((e) => {
        console.warn("Window icon not set", id, e);
        // Not applied: the next refresh (midnight, setting change) tries again.
        if (this.#applied === id) this.#applied = null;
      });
  }

  #arm() {
    clearTimeout(this.#timer);
    const now = this.#deps.now();
    this.#timer = setTimeout(() => {
      this.refresh();
      this.#arm();
    }, msUntilMidnight(now) + MIDNIGHT_SLACK_MS);
  }

  /** Applies the logo and re-evaluates it each midnight. Returns the cleanup. */
  start(): () => void {
    this.refresh();
    this.#arm();
    return () => {
      clearTimeout(this.#timer);
      this.#timer = undefined;
    };
  }

  setSeasonal(on: boolean) {
    this.seasonal = on;
    saveSeasonal(this.#deps.storage, on);
    this.refresh();
  }
}

function localStorageOrNull(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** The app's logo: started once by App.svelte, read by every Logo component. */
export const logo = new LogoState({
  now: () => new Date(),
  storage: localStorageOrNull(),
  applyIcon: (id) =>
    backend === "wails" ? invoke("window_set_icon", { id }) : Promise.resolve(),
});
