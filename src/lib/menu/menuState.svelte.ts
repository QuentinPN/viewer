// The one card menu of the library screen: cards ask for it through
// the Svelte context, the host component shows it and runs its actions.
import { getContext, setContext } from "svelte";
import type { PlaybackStopped } from "../emby/types";
import type { MenuContext, MenuTarget } from "./actions";
import type { Box } from "./position";

export interface MenuRequest {
  target: MenuTarget;
  context: MenuContext;
  /** What the menu opens next to: the "…" button, or the pointer. */
  anchor: Box;
  /** The card: focused again when the menu closes. */
  opener: HTMLElement;
  /** Replaces the default playback: the home rows show their own spinner. */
  play?: (() => void) | undefined;
  /** Replaces "Marquer comme vu": "Reprendre" takes the card out at once. */
  markPlayed?: (() => void) | undefined;
  removeFromResume?: (() => void) | undefined;
}

export class CardMenuState {
  request = $state<MenuRequest | null>(null);
  // Changes made since the cards were read: the menu labels follow them.
  #favorites = new Map<string, boolean>();
  #watched = new Map<string, { played: boolean; inProgress: boolean }>();

  open(request: MenuRequest) {
    const t = request.target;
    const watched = this.#watched.get(t.id);
    this.request = {
      ...request,
      target: {
        ...t,
        favorite: this.#favorites.get(t.id) ?? t.favorite,
        played: watched?.played ?? t.played,
        inProgress: watched?.inProgress ?? t.inProgress,
      },
    };
  }

  close() {
    this.request = null;
  }

  isOpenFor(opener: HTMLElement | null): boolean {
    return opener !== null && this.request?.opener === opener;
  }

  /** A favourite changed, here or on a detail page. */
  noteFavorite(itemId: string, favorite: boolean) {
    this.#favorites.set(itemId, favorite);
  }

  /** A playback stopped or the watched state changed. */
  noteWatched(state: PlaybackStopped) {
    this.#watched.set(state.itemId, {
      played: state.played,
      inProgress: state.resume !== null,
    });
  }
}

const KEY = Symbol("card-menu");

export function provideCardMenu(state: CardMenuState) {
  setContext(KEY, state);
}

/** `undefined` outside the library screen: cards then show no menu. */
export function useCardMenu(): CardMenuState | undefined {
  return getContext<CardMenuState | undefined>(KEY);
}

export function boxOf(el: Element): Box {
  const r = el.getBoundingClientRect();
  return { left: r.left, top: r.top, width: r.width, height: r.height };
}
