// Pure helpers of the pre-connection shelf (ADR 0103): tile identity, labels,
// what a tile does, and keyboard moves between tiles. Covered by Vitest.
import type { EmbyError, ShelfServer } from "../emby/types";

/** One tile per server and user. */
export function tileKey(tile: ShelfServer): string {
  return `${tile.server.id}/${tile.server.userId}`;
}

/** "192.0.2.14:8096" from "http://192.0.2.14:8096/": shown under the name. */
export function hostOf(url: string): string {
  return url
    .trim()
    .replace(/^[a-z][a-z0-9+.-]*:\/\//i, "")
    .replace(/\/+$/, "");
}

/** Big letter of the tile: first letter of the server name, or "?". */
export function initialOf(name: string): string {
  const first = Array.from(name.trim())[0];
  return first ? first.toLocaleUpperCase("fr-FR") : "?";
}

/** Stable hue (0–359) of a server, so its tile keeps its color across launches. */
export function tileHue(serverId: string): number {
  let hash = 0;
  for (const ch of serverId) {
    hash = (hash * 31 + (ch.codePointAt(0) ?? 0)) >>> 0;
  }
  return hash % 360;
}

/** Accessible name of a tile, in normal and in « Gérer » mode. */
export function tileLabel(tile: ShelfServer, manage: boolean): string {
  const who = `${tile.server.name} (${tile.server.userName})`;
  if (manage) return `Oublier ${who}`;
  return tile.access === "rejected"
    ? `Se connecter à ${who}, mot de passe requis`
    : `Se connecter à ${who}`;
}

/**
 * What choosing a tile does: a saved token is validated (`resume`, no password);
 * otherwise the password card opens at once, without any request.
 */
export function tileAction(tile: ShelfServer): "resume" | "password" {
  return tile.access === "session" ? "resume" : "password";
}

/** Notice of the password card: only a rejection seen before says « Session expirée ». */
export function passwordNotice(tile: ShelfServer): EmbyError | null {
  return tile.access === "rejected"
    ? {
        kind: "sessionExpired",
        message: "session expirée ou révoquée : reconnecte-toi",
      }
    : null;
}

/** Plain HTTP (also an address typed without scheme, which defaults to http). */
export function isPlainHttp(url: string): boolean {
  const trimmed = url.trim();
  return trimmed !== "" && !/^https:\/\//i.test(trimmed);
}

/**
 * Index focused after `key` on tile `index` of `count`, laid out in rows of
 * `columns`; `null` when the key does not move. Never wraps around.
 */
export function moveIndex(
  index: number,
  key: string,
  count: number,
  columns: number,
): number | null {
  if (count <= 0) return null;
  const cols = Math.max(1, Math.floor(columns));
  let next: number;
  switch (key) {
    case "ArrowLeft":
      next = index - 1;
      break;
    case "ArrowRight":
      next = index + 1;
      break;
    case "ArrowUp":
      next = index - cols;
      break;
    case "ArrowDown":
      next = index + cols;
      break;
    case "Home":
      next = 0;
      break;
    case "End":
      next = count - 1;
      break;
    default:
      return null;
  }
  if (next < 0 || next >= count) {
    // Down from the last row lands on the last tile, if it is on a later row.
    if (
      key === "ArrowDown" &&
      Math.floor(index / cols) < Math.floor((count - 1) / cols)
    ) {
      return count - 1;
    }
    return null;
  }
  return next;
}

/** Tiles per row, from the top offset of each rendered tile. */
export function columnsOf(tops: number[]): number {
  const [first] = tops;
  if (first === undefined) return 1;
  const n = tops.findIndex((t) => Math.abs(t - first) > 1);
  return n === -1 ? tops.length : n;
}
