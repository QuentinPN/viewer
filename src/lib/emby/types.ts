// Mirrors src-tauri/src/emby/models.rs and EmbyError (ADR 0004). No type here
// ever carries the access token.

export interface ServerSummary {
  url: string;
  name: string;
  version: string;
  id: string;
  /** False for plain HTTP, only accepted on the local network. */
  encrypted: boolean;
}

export interface SignedInUser {
  server: ServerSummary;
  userId: string;
  userName: string;
  isAdministrator: boolean;
}

export type EmbyErrorKind =
  | "invalidUrl"
  | "insecureRemoteUrl"
  | "unreachable"
  | "timeout"
  | "invalidCredentials"
  | "forbidden"
  | "server"
  | "invalidResponse"
  | "notFound"
  | "notSignedIn"
  | "sessionExpired"
  | "accountUnavailable"
  | "serverMismatch"
  | "cancelled"
  | "credentialStore"
  | "playbackRefused"
  | "noCompatibleStream"
  | "player"
  | "settings";

export interface EmbyError {
  kind: EmbyErrorKind;
  message: string;
}

/** A session known from `session.json` (ADR 0012). Never carries the token. */
export interface SavedServer {
  url: string;
  id: string;
  name: string;
  version: string;
  encrypted: boolean;
  userId: string;
  userName: string;
}

/** Outcome of `emby_restore`: only a rejection asks for the password again. */
export type SessionStatus =
  | { state: "signedOut" }
  | { state: "authenticated"; user: SignedInUser }
  | { state: "serverUnreachable"; server: SavedServer; error: EmbyError }
  | { state: "authenticationRequired"; server: SavedServer; error: EmbyError };

/** Artwork on the local `emby-img` protocol (ADR 0005), ready for `<img>`. */
export interface ImageRef {
  src: string;
  srcset: string;
}

export type LibraryKind = "movies" | "tvShows";

export interface Library {
  id: string;
  name: string;
  kind: LibraryKind;
  image: ImageRef | null;
}

export interface MediaItem {
  id: string;
  name: string;
  itemType: string;
  year: number | null;
  runtimeMinutes: number | null;
  poster: ImageRef | null;
  aspectRatio: number | null;
  played: boolean;
  /** Series only: episodes not yet watched. */
  unplayedCount: number | null;
}

/** Films, series and episodes whose title words start with the query (ADR 0011, 0020). */
export interface SearchResults {
  /** The query as sent to the server, after cleaning; empty when too short. */
  query: string;
  movies: MediaItem[];
  series: MediaItem[];
  /** Matched on their own title, not their series name. */
  episodes: WatchItem[];
  /** More than shown: the server's total is not reliable with a search. */
  moreMovies: boolean;
  moreSeries: boolean;
  /** Maybe more: the server also returns episodes of matching series. */
  moreEpisodes: boolean;
}

/** A film or episode of the home rows "Reprendre" and "À suivre" (ADR 0010). */
export interface WatchItem {
  id: string;
  name: string;
  kind: "movie" | "episode";
  /** Episodes only. */
  seriesName: string | null;
  seasonIndex: number | null;
  index: number | null;
  /** 16:9: episode still, else the series or film thumb, else a backdrop. */
  image: ImageRef | null;
  runtimeMinutes: number | null;
  resume: ResumePoint | null;
}

/** One title of the home hero (ADR 0015). */
export interface HeroItem {
  /** Film or series the detail page opens; a resumed episode gives its series. */
  detail: MediaItem;
  reason: "resume" | "latest";
  backdrop: ImageRef;
  logo: ImageRef | null;
  overview: string | null;
  /** Film or episode the main button plays; `null` when nothing is left. */
  playId: string | null;
  episode: HeroEpisode | null;
  resume: ResumePoint | null;
}

export interface HeroEpisode {
  name: string;
  seasonIndex: number | null;
  index: number | null;
}

/** Sort of a library grid (ADR 0013); Rust maps it to `SortBy`. */
export type SortKey = "name" | "dateAdded" | "year" | "rating";
/** Watched-state filter; `inProgress` is offered for films only. */
export type WatchFilter = "all" | "unplayed" | "played" | "inProgress";

/** Sort and filters of a library grid, sent with `emby_items`. */
export interface ItemsOptions {
  sort: SortKey;
  descending: boolean;
  watch: WatchFilter;
  favorites: boolean;
  /** One genre among `emby_genres`, or none. */
  genre: string | null;
}

export interface ItemPage {
  items: MediaItem[];
  startIndex: number;
  /** Start of the next page, counting items Rust dropped as unusable. */
  nextIndex: number;
  total: number;
}

export type DetailKind = "movie" | "series" | "episode";

/** Neighbour of an episode in its series (ADR 0016). */
export interface EpisodeLink {
  id: string;
  name: string;
  seasonIndex: number | null;
  index: number | null;
  indexEnd: number | null;
}

/** Where an episode sits in its series (ADR 0016). */
export interface EpisodeContext {
  seriesId: string;
  seriesName: string;
  seasonId: string | null;
  /** 0 is the specials season. */
  seasonIndex: number | null;
  index: number | null;
  indexEnd: number | null;
  /** In server order, across seasons. */
  previous: EpisodeLink | null;
  next: EpisodeLink | null;
}

/** Where playback stopped on a started, unfinished item. */
export interface ResumePoint {
  positionSeconds: number;
  /** 0–1 of the runtime, when known. */
  fraction: number | null;
}

export interface CastMember {
  name: string;
  role: string | null;
}

export interface VideoInfo {
  codec: string | null;
  width: number | null;
  height: number | null;
  bitDepth: number | null;
  /** `SDR`, `HDR 10`, `Dolby Vision Profile 8.1 (…)`. */
  range: string | null;
}

/** Audio or subtitle track as stored in the file (ISO 639 language code). */
export interface StreamInfo {
  language: string | null;
  codec: string | null;
  title: string | null;
  channels: number | null;
  isDefault: boolean;
  isForced: boolean;
  isExternal: boolean;
}

/** One version of a film. Says nothing about whether it plays directly. */
export interface MediaInfo {
  name: string | null;
  container: string | null;
  sizeBytes: number | null;
  bitrate: number | null;
  video: VideoInfo | null;
  audio: StreamInfo[];
  subtitles: StreamInfo[];
}

export interface ItemDetail {
  id: string;
  kind: DetailKind;
  name: string;
  originalTitle: string | null;
  year: number | null;
  endYear: number | null;
  continuing: boolean | null;
  overview: string | null;
  tagline: string | null;
  genres: string[];
  studios: string[];
  directors: string[];
  writers: string[];
  /** Actors; guest stars for an episode. */
  cast: CastMember[];
  officialRating: string | null;
  communityRating: number | null;
  criticRating: number | null;
  runtimeMinutes: number | null;
  /** ISO 8601 in UTC; format it in local time. */
  premiereDate: string | null;
  poster: ImageRef | null;
  backdrop: ImageRef | null;
  logo: ImageRef | null;
  played: boolean;
  /** In the user's favorites (ADR 0014). */
  favorite: boolean;
  resume: ResumePoint | null;
  unplayedCount: number | null;
  media: MediaInfo[];
  /** Episode only. */
  episode: EpisodeContext | null;
}

export interface Season {
  id: string;
  name: string;
  /** 0 is the specials season. */
  index: number | null;
  unplayedCount: number | null;
  played: boolean;
}

export interface Episode {
  id: string;
  name: string;
  index: number | null;
  indexEnd: number | null;
  /** 0 for a special shown within a regular season. */
  seasonIndex: number | null;
  overview: string | null;
  runtimeMinutes: number | null;
  premiereDate: string | null;
  thumb: ImageRef | null;
  played: boolean;
  resume: ResumePoint | null;
}

export interface EpisodePage {
  items: Episode[];
  startIndex: number;
  nextIndex: number;
  total: number;
}

/** How the server delivers the media (ADR 0007). */
export type PlayMethod = "directPlay" | "directStream" | "transcode";

/** A started playback: the method only, never the stream URL or the token. */
export interface PlaybackStart {
  itemId: string;
  /** The start asked for: a fallback keeps "Reprendre" or "Lire depuis le début". */
  resume: boolean;
  method: PlayMethod;
  container: string | null;
  /** Why the server transcodes, in French ("débit trop élevé"); null otherwise. */
  transcodeReasons: string | null;
  /** What the player shows at the top (ADR 0017); null when the item could not be read. */
  title: PlaybackTitle | null;
  /** Bitrate cap sent to the server, in bit/s (ADR 0037); null for "Maximale". */
  bitrateCap: number | null;
  /** Whether the server address counts as local, so which cap applied. */
  localAddress: boolean;
}

/** Streaming bitrate caps of the current server, in bit/s; null is "Maximale". */
export interface PlaybackQuality {
  local: number | null;
  remote: number | null;
  /** Whether the session's address counts as local, so which cap applies. */
  localAddress: boolean;
  /** Caps offered, highest first. */
  choices: number[];
}

/** Name of what plays and, for an episode, where it sits in its series. */
export interface PlaybackTitle {
  name: string;
  seriesName: string | null;
  /** `0` is the specials season. */
  seasonIndex: number | null;
  index: number | null;
  indexEnd: number | null;
}

/** Server state of an item once its playback stopped and was reported (ADR 0008). */
export interface PlaybackStopped {
  itemId: string;
  played: boolean;
  resume: ResumePoint | null;
}

/** The artwork disk cache (ADR 0019); `hits` and `misses` since the app started. */
export interface ArtworkCacheStats {
  entries: number;
  bytes: number;
  quotaBytes: number;
  hits: number;
  misses: number;
}

/** What emptying the artwork cache removed (ADR 0021). */
export interface ArtworkCacheCleared {
  entries: number;
  bytes: number;
}

/** A range the player offers to skip, in seconds of the file (ADR 0030). */
export interface Segment {
  kind: "intro" | "credits";
  start: number;
  /** `null`: credits that run to the end of the file. */
  end: number | null;
  source: "emby" | "introDb" | "theIntroDb";
}
