import type { DelayChange } from "./delay";
import type { PlayerCommand } from "./types";

export type PlayerAction =
  | { kind: "command"; command: PlayerCommand }
  | { kind: "toggleFullscreen" }
  | { kind: "exitFullscreen" }
  | { kind: "toggleMute" }
  | { kind: "volume"; delta: number }
  | { kind: "toggleInfo" }
  | { kind: "chapter"; delta: -1 | 1 }
  | { kind: "subtitleDelay"; change: DelayChange };

/** Volume step of the arrow keys, in percent. */
export const VOLUME_STEP = 5;

/**
 * Maps a key press to a player action. Returns null for keys the player does not
 * handle, and when focus is in a text field so typing is never hijacked.
 */
export function mapPlayerKey(
  key: string,
  opts: { inTextField: boolean; shift?: boolean },
): PlayerAction | null {
  if (opts.inTextField) return null;
  const step = opts.shift ? 30 : 10;
  // Letters work with Caps Lock or Shift too.
  switch (key.length === 1 ? key.toLowerCase() : key) {
    case " ":
    case "k":
      return { kind: "command", command: { type: "togglePause" } };
    case "ArrowLeft":
      return {
        kind: "command",
        command: { type: "seekRelative", seconds: -step },
      };
    case "ArrowRight":
      return {
        kind: "command",
        command: { type: "seekRelative", seconds: step },
      };
    case "ArrowUp":
      return { kind: "volume", delta: VOLUME_STEP };
    case "ArrowDown":
      return { kind: "volume", delta: -VOLUME_STEP };
    case "m":
      return { kind: "toggleMute" };
    case "i":
      return { kind: "toggleInfo" };
    case "f":
      return { kind: "toggleFullscreen" };
    case "Escape":
      return { kind: "exitFullscreen" };
    // Previous and next chapter, as in mpv.
    case "PageUp":
      return { kind: "chapter", delta: -1 };
    case "PageDown":
      return { kind: "chapter", delta: 1 };
    // Subtitle delay, as in mpv.
    case "z":
      return { kind: "subtitleDelay", change: "earlier" };
    case "x":
      return { kind: "subtitleDelay", change: "later" };
    default:
      return null;
  }
}
