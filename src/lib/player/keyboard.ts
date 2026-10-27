import type { PlayerCommand } from "./types";

export type PlayerAction =
  | { kind: "command"; command: PlayerCommand }
  | { kind: "toggleFullscreen" }
  | { kind: "exitFullscreen" };

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
  switch (key) {
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
    case "f":
      return { kind: "toggleFullscreen" };
    case "Escape":
      return { kind: "exitFullscreen" };
    default:
      return null;
  }
}
