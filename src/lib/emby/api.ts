import { invoke, listen, type Unlisten } from "../native";
import { preferenceStorage, readPreferences } from "../player/subtitles";
import type {
  ArtworkCacheCleared,
  ArtworkCacheStats,
  Chapter,
  EmbyError,
  EpisodePage,
  HeroItem,
  ItemDetail,
  ItemPage,
  ItemsOptions,
  Library,
  MediaItem,
  PlaybackQuality,
  PlaybackStart,
  PlaybackStopped,
  Playlist,
  PlaylistCard,
  PlaylistCreated,
  SearchResults,
  Season,
  Segment,
  ServerSummary,
  SessionStatus,
  ShelfServer,
  SignedInUser,
  SortNameEntry,
  TitlesSource,
  WatchItem,
} from "./types";

// Thin typed wrapper over the Emby commands of go-app/cmd/viewer/commands_emby.go.

export const PLAYBACK_STOPPED_EVENT = "emby://playback-stopped";
/** Titles added, changed or removed on the server. */
export const LIBRARY_CHANGED_EVENT = "emby://library-changed";

export const embyApi = {
  probe: (url: string) => invoke<ServerSummary>("emby_probe", { url }),
  /** The signed-in server as it answers now; rejects when it cannot be reached. */
  serverInfo: () => invoke<ServerSummary>("emby_server_info"),
  signIn: (url: string, username: string, password: string) =>
    invoke<SignedInUser>("emby_sign_in", { url, username, password }),
  /** Validates the saved session; `cancelled` if superseded. */
  restore: () => invoke<SessionStatus>("emby_restore"),
  /** Sets the session aside without revoking it, to come back to it later. */
  changeServer: () => invoke<void>("emby_change_server"),
  /** Tiles of the pre-connection shelf. */
  shelf: () => invoke<ShelfServer[]>("emby_shelf"),
  /** Forgets a server on this device; nothing is deleted on the server. */
  forgetServer: (serverId: string, userId: string) =>
    invoke<void>("emby_forget_server", { serverId, userId }),
  resumeSaved: (serverId: string, userId: string) =>
    invoke<SessionStatus>("emby_resume_saved", { serverId, userId }),
  signOut: () => invoke<void>("emby_sign_out"),
  lastServer: () => invoke<string | null>("emby_last_server"),
  libraries: () => invoke<Library[]>("emby_libraries"),
  items: (
    libraryId: string,
    startIndex: number,
    limit: number,
    options?: ItemsOptions,
  ) =>
    invoke<ItemPage>("emby_items", { libraryId, startIndex, limit, options }),
  /** Every title of the grid in its order, with its sort name. */
  sortNames: (libraryId: string, options: ItemsOptions) =>
    invoke<SortNameEntry[]>("emby_sort_names", { libraryId, options }),
  /** One title drawn by the server among those the grid shows. */
  randomItem: (libraryId: string, options: ItemsOptions) =>
    invoke<MediaItem | null>("emby_random_item", { libraryId, options }),
  /** Production years of a library, newest first. */
  productionYears: (libraryId: string) =>
    invoke<number[]>("emby_production_years", { libraryId }),
  /** Genre names of a library, usable as a filter. */
  genres: (libraryId: string) => invoke<string[]>("emby_genres", { libraryId }),
  continueWatching: () => invoke<WatchItem[]>("emby_continue_watching"),
  nextUp: () => invoke<WatchItem[]>("emby_next_up"),
  latest: (libraryId: string) =>
    invoke<MediaItem[]>("emby_latest", { libraryId }),
  /** Home row "Favoris": favorite films and series, by title. */
  favorites: () => invoke<MediaItem[]>("emby_favorites"),
  /** Home hero: up to five titles, the ones to resume first. */
  hero: () => invoke<HeroItem[]>("emby_hero"),
  /** Returns the favorite state the server reports after the change. */
  setFavorite: (itemId: string, favorite: boolean) =>
    invoke<boolean>("emby_set_favorite", { itemId, favorite }),
  /**
   * Marks a film, episode, season or series played or unplayed. The
   * new state is returned and also sent on the playback-stopped event, so every
   * view showing the item follows.
   */
  setPlayed: (itemId: string, played: boolean) =>
    invoke<PlaybackStopped>("emby_set_played", { itemId, played }),
  /** Takes a film or episode out of "Reprendre"; announced the same way. */
  hideFromResume: (itemId: string) =>
    invoke<PlaybackStopped>("emby_hide_from_resume", { itemId }),
  /** First episode of a series not watched yet, as the hero plays it. */
  seriesNextEpisode: (seriesId: string) =>
    invoke<WatchItem | null>("emby_series_next_episode", { seriesId }),
  /** Playlists of the user, by name. */
  playlists: () => invoke<Playlist[]>("emby_playlists"),
  /** Home row « Playlists »: video playlists, A → Z. */
  homePlaylists: () => invoke<PlaylistCard[]>("emby_home_playlists"),
  /** Films and episodes of a playlist, in its order. */
  playlistItems: (playlistId: string) =>
    invoke<WatchItem[]>("emby_playlist_items", { playlistId }),
  /** Returns how many items the server says it added. */
  addToPlaylist: (playlistId: string, itemId: string) =>
    invoke<number>("emby_add_to_playlist", { playlistId, itemId }),
  /** Creates a video playlist holding the item. */
  createPlaylist: (name: string, itemId: string) =>
    invoke<PlaylistCreated>("emby_create_playlist", { name, itemId }),
  search: (query: string) => invoke<SearchResults>("emby_search", { query }),
  itemDetail: (itemId: string) =>
    invoke<ItemDetail>("emby_item_detail", { itemId }),
  seasons: (seriesId: string) => invoke<Season[]>("emby_seasons", { seriesId }),
  /** Titles of a person, collection, genre or studio. */
  titles: (source: TitlesSource) =>
    invoke<MediaItem[]>("emby_titles", { source }),
  /** Id of the first trailer file of a film or series, to play. */
  localTrailer: (itemId: string) =>
    invoke<string | null>("emby_local_trailer", { itemId }),
  episodes: (
    seriesId: string,
    seasonId: string,
    startIndex: number,
    limit: number,
  ) =>
    invoke<EpisodePage>("emby_episodes", {
      seriesId,
      seasonId,
      startIndex,
      limit,
    }),
  /**
   * Negotiates with the server and starts the player. With `resume`,
   * starts where the server says the user stopped. With
   * `transcodeFallback`, direct play is refused to the server, which then
   * transcodes: the way out when the player could not open the direct source.
   * `mediaSourceId` plays one version of an item with several; null
   * lets the server pick. The audio and subtitle preferences of
   * Réglages go along.
   */
  play: (
    itemId: string,
    resume: boolean,
    transcodeFallback = false,
    mediaSourceId: string | null = null,
  ) =>
    invoke<PlaybackStart>("emby_play", {
      itemId,
      resume,
      transcodeFallback,
      mediaSourceId,
      preferences: readPreferences(preferenceStorage()),
    }),
  /**
   * Another bitrate cap for the playback in progress, from `position`
   * seconds; `null` is « Maximale ». Réglages is left as it is.
   */
  changeQuality: (
    itemId: string,
    mediaSourceId: string,
    cap: number | null,
    positionSeconds: number,
  ) =>
    invoke<PlaybackStart>("emby_change_quality", {
      itemId,
      mediaSourceId,
      cap,
      positionSeconds,
      preferences: readPreferences(preferenceStorage()),
    }),
  /**
   * Intro and credits to offer skipping. `external: false` asks the
   * Emby server only. Never fails for an unreachable external source.
   */
  segments: (itemId: string, external: boolean) =>
    invoke<Segment[]>("emby_segments", { itemId, external }),
  /** Chapters of a film or episode, none when it has fewer than two. */
  chapters: (itemId: string) => invoke<Chapter[]>("emby_chapters", { itemId }),
  /** `null` when the app has no artwork cache directory. */
  artworkStats: () => invoke<ArtworkCacheStats | null>("artwork_cache_stats"),
  /** Empties the artwork cache of every server. */
  clearArtwork: () => invoke<ArtworkCacheCleared | null>("artwork_cache_clear"),
  /** Bitrate caps of the current server. */
  playbackQuality: () => invoke<PlaybackQuality>("emby_playback_quality"),
  /** Keeps both caps; a value not offered is refused. */
  setPlaybackQuality: (local: number | null, remote: number | null) =>
    invoke<PlaybackQuality>("emby_set_playback_quality", { local, remote }),
  /**
   * The item's new state, once a playback stopped and was reported, or once it
   * was marked played or unplayed or taken out of "Reprendre".
   */
  /**
   * Calls `handler` each time titles were added, changed or removed on the
   * server, while the returned function has not been called.
   */
  watchLibrary: async (handler: () => void): Promise<() => void> => {
    const unlisten = await listen(LIBRARY_CHANGED_EVENT, () => handler());
    try {
      await invoke("emby_library_events_start");
    } catch (e) {
      unlisten();
      throw e;
    }
    return () => {
      unlisten();
      void invoke("emby_library_events_stop").catch(() => {});
    };
  },
  onPlaybackStopped: (
    handler: (state: PlaybackStopped) => void,
  ): Promise<Unlisten> =>
    listen<PlaybackStopped>(PLAYBACK_STOPPED_EVENT, handler),
};

/** Normalizes a rejected command into an `EmbyError`, whatever was thrown. */
export function toEmbyError(e: unknown): EmbyError {
  if (
    typeof e === "object" &&
    e !== null &&
    "kind" in e &&
    "message" in e &&
    typeof e.message === "string"
  ) {
    return e as EmbyError;
  }
  return {
    kind: "invalidResponse",
    message: typeof e === "string" ? e : "erreur inattendue",
  };
}

/** One-line hint telling the user what to do next, in French. */
export function errorHint(error: EmbyError): string | null {
  switch (error.kind) {
    case "unreachable":
    case "timeout":
      return "Vérifie l'adresse, le port (8096 par défaut) et que le serveur est allumé.";
    case "insecureRemoteUrl":
      return "Utilise une adresse https://, ou l'adresse locale du serveur.";
    case "invalidCredentials":
      return "Le nom d'utilisateur et le mot de passe sont sensibles à la casse.";
    case "sessionExpired":
      return "Ta session a été fermée sur le serveur. Reconnecte-toi.";
    case "accountUnavailable":
      return "Demande à l'administrateur du serveur de réactiver ton compte, ou utilises-en un autre.";
    case "serverMismatch":
      return "Ta session n'a pas été envoyée à ce serveur. Vérifie l'adresse ou change de serveur.";
    case "invalidResponse":
      return "L'adresse répond, mais ce n'est peut-être pas un serveur Emby.";
    case "notFound":
      return "Elle a peut-être été supprimée ou masquée. Actualise la liste.";
    case "credentialStore":
      return "Le Gestionnaire d'identification Windows n'a pas pu enregistrer la session.";
    case "noCompatibleStream":
      return "Les informations techniques de la fiche indiquent le format du fichier.";
    case "player":
      return "Vérifie que libmpv-2.dll est présent (scripts/fetch-libmpv.ps1).";
    default:
      return null;
  }
}
