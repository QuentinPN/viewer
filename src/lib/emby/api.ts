import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type {
  ArtworkCacheCleared,
  ArtworkCacheStats,
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
  SavedServer,
  SearchResults,
  Season,
  Segment,
  ServerSummary,
  SessionStatus,
  SignedInUser,
  WatchItem,
} from "./types";

// Thin typed wrapper over the Emby commands of src-tauri/src/commands.rs.

export const PLAYBACK_STOPPED_EVENT = "emby://playback-stopped";

export const embyApi = {
  probe: (url: string) => invoke<ServerSummary>("emby_probe", { url }),
  signIn: (url: string, username: string, password: string) =>
    invoke<SignedInUser>("emby_sign_in", { url, username, password }),
  /** Validates the saved session (ADR 0012); `cancelled` if superseded. */
  restore: () => invoke<SessionStatus>("emby_restore"),
  /** Sets the session aside without revoking it, to come back to it later. */
  changeServer: () => invoke<void>("emby_change_server"),
  savedSessions: () => invoke<SavedServer[]>("emby_saved_sessions"),
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
  /** Genre names of a library, usable as a filter (ADR 0013). */
  genres: (libraryId: string) => invoke<string[]>("emby_genres", { libraryId }),
  continueWatching: () => invoke<WatchItem[]>("emby_continue_watching"),
  nextUp: () => invoke<WatchItem[]>("emby_next_up"),
  latest: (libraryId: string) =>
    invoke<MediaItem[]>("emby_latest", { libraryId }),
  /** Home row "Favoris" (ADR 0014): favorite films and series, by title. */
  favorites: () => invoke<MediaItem[]>("emby_favorites"),
  /** Home hero (ADR 0015): up to five titles, the ones to resume first. */
  hero: () => invoke<HeroItem[]>("emby_hero"),
  /** Returns the favorite state the server reports after the change. */
  setFavorite: (itemId: string, favorite: boolean) =>
    invoke<boolean>("emby_set_favorite", { itemId, favorite }),
  search: (query: string) => invoke<SearchResults>("emby_search", { query }),
  itemDetail: (itemId: string) =>
    invoke<ItemDetail>("emby_item_detail", { itemId }),
  seasons: (seriesId: string) => invoke<Season[]>("emby_seasons", { seriesId }),
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
   * Negotiates with the server and starts the player (ADR 0007). With `resume`,
   * starts where the server says the user stopped (ADR 0008). With
   * `transcodeFallback`, direct play is refused to the server, which then
   * transcodes: the way out when the player could not open the direct source
   * (ADR 0023).
   */
  play: (itemId: string, resume: boolean, transcodeFallback = false) =>
    invoke<PlaybackStart>("emby_play", { itemId, resume, transcodeFallback }),
  /**
   * Intro and credits to offer skipping (ADR 0030). `external: false` asks the
   * Emby server only. Never fails for an unreachable external source.
   */
  segments: (itemId: string, external: boolean) =>
    invoke<Segment[]>("emby_segments", { itemId, external }),
  /** `null` when the app has no artwork cache directory. */
  artworkStats: () => invoke<ArtworkCacheStats | null>("artwork_cache_stats"),
  /** Empties the artwork cache of every server (ADR 0021). */
  clearArtwork: () => invoke<ArtworkCacheCleared | null>("artwork_cache_clear"),
  /** Bitrate caps of the current server (ADR 0037). */
  playbackQuality: () => invoke<PlaybackQuality>("emby_playback_quality"),
  /** Keeps both caps; a value not offered is refused. */
  setPlaybackQuality: (local: number | null, remote: number | null) =>
    invoke<PlaybackQuality>("emby_set_playback_quality", { local, remote }),
  /** The item's new state, once a playback stopped and was reported. */
  onPlaybackStopped: (
    handler: (state: PlaybackStopped) => void,
  ): Promise<UnlistenFn> =>
    listen<PlaybackStopped>(PLAYBACK_STOPPED_EVENT, (e) => handler(e.payload)),
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
      return "Vérifiez l'adresse, le port (8096 par défaut) et que le serveur est allumé.";
    case "insecureRemoteUrl":
      return "Utilisez une adresse https://, ou l'adresse locale du serveur.";
    case "invalidCredentials":
      return "Le nom d'utilisateur et le mot de passe sont sensibles à la casse.";
    case "sessionExpired":
      return "Votre session a été fermée sur le serveur. Reconnectez-vous.";
    case "accountUnavailable":
      return "Demandez à l'administrateur du serveur de réactiver votre compte, ou utilisez-en un autre.";
    case "serverMismatch":
      return "Votre session n'a pas été envoyée à ce serveur. Vérifiez l'adresse ou changez de serveur.";
    case "invalidResponse":
      return "L'adresse répond, mais ce n'est peut-être pas un serveur Emby.";
    case "notFound":
      return "Elle a peut-être été supprimée ou masquée. Actualisez la liste.";
    case "credentialStore":
      return "Le Gestionnaire d'identification Windows n'a pas pu enregistrer la session.";
    case "noCompatibleStream":
      return "Les informations techniques de la fiche indiquent le format du fichier.";
    case "player":
      return "Vérifiez que libmpv-2.dll est présent (scripts/fetch-libmpv.ps1).";
    default:
      return null;
  }
}
