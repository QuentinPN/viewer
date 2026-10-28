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
  | "credentialStore"
  | "playbackRefused"
  | "noCompatibleStream"
  | "player";

export interface EmbyError {
  kind: EmbyErrorKind;
  message: string;
}

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

export interface ItemPage {
  items: MediaItem[];
  startIndex: number;
  /** Start of the next page, counting items Rust dropped as unusable. */
  nextIndex: number;
  total: number;
}

export type DetailKind = "movie" | "series";

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
  resume: ResumePoint | null;
  unplayedCount: number | null;
  media: MediaInfo[];
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
  method: PlayMethod;
  container: string | null;
  /** Why the server transcodes, in French ("débit trop élevé"); null otherwise. */
  transcodeReasons: string | null;
}

/** Server state of an item once its playback stopped and was reported (ADR 0008). */
export interface PlaybackStopped {
  itemId: string;
  played: boolean;
  resume: ResumePoint | null;
}
