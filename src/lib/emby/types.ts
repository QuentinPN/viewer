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
  | "credentialStore";

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

export interface ItemPage {
  items: MediaItem[];
  startIndex: number;
  /** Start of the next page, counting items Rust dropped as unusable. */
  nextIndex: number;
  total: number;
}
