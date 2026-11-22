// Card menu: which actions a card offers, in the order of the
// official Emby apps, and the labels the menu shows.
import type { ImageRef, MediaItem, WatchItem } from "../emby/types";
import { episodeCode } from "../home/format";

export type MenuKind = "movie" | "episode" | "series";

/**
 * Where the card is: "resume" for the home row "Reprendre", the only one that
 * offers to take the item out of it.
 */
export type MenuContext = "resume" | "nextUp" | "poster" | "search";

/** What the menu needs of a card, whatever its kind. */
export interface MenuTarget {
  id: string;
  name: string;
  kind: MenuKind;
  /** Header line under the title: the year, or the series and episode. */
  subtitle: string | null;
  image: ImageRef | null;
  /** 16:9 image instead of a 2:3 poster. */
  wide: boolean;
  /** Has a resume point: the first action resumes. */
  inProgress: boolean;
  played: boolean;
  favorite: boolean;
}

export type MenuActionId =
  | "play"
  | "resume"
  | "playNext"
  | "playlist"
  | "played"
  | "unplayed"
  | "favorite"
  | "unfavorite"
  | "removeResume";

export type MenuIcon =
  | "play"
  | "playlist"
  | "check"
  | "uncheck"
  | "heart"
  | "heartOff"
  | "remove";

export interface MenuAction {
  id: MenuActionId;
  label: string;
  icon: MenuIcon;
}

/**
 * Actions of a card, in order: play (resume, or the next episode of a series),
 * add to a playlist, played, favourite, then "Retirer de Reprendre" on that row.
 * No "Tout lire" or "Aléatoire": the app plays no queue.
 */
export function menuActions(
  target: MenuTarget,
  context: MenuContext,
): MenuAction[] {
  const first: MenuAction =
    target.kind === "series"
      ? { id: "playNext", label: "Lire le prochain épisode", icon: "play" }
      : target.inProgress
        ? { id: "resume", label: "Reprendre", icon: "play" }
        : { id: "play", label: "Lire", icon: "play" };
  const actions: MenuAction[] = [
    first,
    { id: "playlist", label: "Ajouter à une playlist", icon: "playlist" },
    target.played
      ? { id: "unplayed", label: "Marquer comme non vu", icon: "uncheck" }
      : { id: "played", label: "Marquer comme vu", icon: "check" },
    target.favorite
      ? { id: "unfavorite", label: "Retirer des favoris", icon: "heartOff" }
      : { id: "favorite", label: "Ajouter aux favoris", icon: "heart" },
  ];
  if (context === "resume" && target.kind !== "series") {
    actions.push({
      id: "removeResume",
      label: "Retirer de Reprendre",
      icon: "remove",
    });
  }
  return actions;
}

/** Menu target of a poster card; `null` for a kind the menu does not handle. */
export function posterTarget(item: MediaItem): MenuTarget | null {
  const kind = KINDS[item.itemType];
  if (!kind) return null;
  return {
    id: item.id,
    name: item.name,
    kind,
    subtitle: item.year === null ? null : String(item.year),
    image: item.poster,
    wide: false,
    inProgress: false,
    played: item.played,
    favorite: item.favorite,
  };
}

/** Menu target of a 16:9 card of "Reprendre", "À suivre" or the search. */
export function watchTarget(item: WatchItem): MenuTarget {
  return {
    id: item.id,
    name: item.name,
    kind: item.kind,
    subtitle:
      item.kind === "episode"
        ? [item.seriesName, episodeCode(item)].filter(Boolean).join(" · ") ||
          null
        : null,
    image: item.image,
    wide: true,
    inProgress: item.resume !== null,
    played: item.played,
    favorite: item.favorite,
  };
}

/** The keys that open a focused card's menu: the context-menu key, Maj + F10. */
export function isMenuKey(e: Pick<KeyboardEvent, "key" | "shiftKey">): boolean {
  return e.key === "ContextMenu" || (e.key === "F10" && e.shiftKey);
}

const KINDS: Record<string, MenuKind | undefined> = {
  Movie: "movie",
  Series: "series",
  Episode: "episode",
};

/** Longest playlist name the server is sent, as checked again in Rust. */
export const PLAYLIST_NAME_MAX = 200;

/** The name to send, or `null` when it cannot be one. */
export function playlistName(value: string): string | null {
  const name = value.trim();
  if (name.length === 0 || [...name].length > PLAYLIST_NAME_MAX) return null;
  // biome-ignore lint/suspicious/noControlCharactersInRegex: refused on purpose
  if (/[\u0000-\u001f\u007f-\u009f]/.test(name)) return null;
  return name;
}

/**
 * Confirmation once the server answered. Why it may add nothing (the item
 * already there?) is not documented: the message only says what it reported.
 */
export function addedMessage(
  title: string,
  playlist: string,
  added: number,
): string {
  return added > 0
    ? `« ${title} » ajouté à « ${playlist} »`
    : `Le serveur n'a rien ajouté à « ${playlist} » : « ${title} » y est peut-être déjà`;
}
