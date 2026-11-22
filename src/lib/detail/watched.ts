// Watched actions: mark played or unplayed, take out of "Reprendre".
import type { PlaybackStopped } from "../emby/types";

/**
 * Optimistic change, as for favorites: `show` gets the wanted state at once,
 * then the state the server reports, or the previous state back when the request
 * fails (the error is rethrown for the caller to report).
 */
export async function changePlayed(
  wanted: boolean,
  show: (played: boolean) => void,
  send: (played: boolean) => Promise<PlaybackStopped>,
): Promise<PlaybackStopped> {
  show(wanted);
  try {
    const state = await send(wanted);
    show(state.played);
    return state;
  } catch (e) {
    show(!wanted);
    throw e;
  }
}

/** Label of the toggle: what activating it does. */
export function playedAction(played: boolean, name?: string): string {
  const what = name ? `« ${name} »` : "";
  return played
    ? ["Marquer", what, "comme non vu"].filter(Boolean).join(" ")
    : ["Marquer", what, "comme vu"].filter(Boolean).join(" ");
}

interface Watchable {
  id: string;
  played: boolean;
  unplayedCount?: number | null;
}

/**
 * Applies an item's new state to the cards that show it; returns whether one did.
 * The unwatched count of a series is unknown until it is read again: the stale
 * one is dropped rather than shown.
 */
export function patchPlayed(
  items: Watchable[],
  state: Pick<PlaybackStopped, "itemId" | "played">,
): boolean {
  let found = false;
  for (const item of items) {
    if (item.id !== state.itemId) continue;
    found = true;
    if (item.played === state.played) continue;
    item.played = state.played;
    if (item.unplayedCount !== undefined) item.unplayedCount = null;
  }
  return found;
}

/** The list without `id`, and where it was, to put it back on failure. */
export function withoutItem<T extends { id: string }>(
  items: T[],
  id: string,
): { items: T[]; removed: { item: T; index: number } | null } {
  const index = items.findIndex((i) => i.id === id);
  if (index < 0) return { items, removed: null };
  return {
    items: [...items.slice(0, index), ...items.slice(index + 1)],
    removed: { item: items[index] as T, index },
  };
}

/** Puts back a removed item at its place, unless it came back meanwhile. */
export function restoreItem<T extends { id: string }>(
  items: T[],
  removed: { item: T; index: number },
): T[] {
  if (items.some((i) => i.id === removed.item.id)) return items;
  const at = Math.min(removed.index, items.length);
  return [...items.slice(0, at), removed.item, ...items.slice(at)];
}
