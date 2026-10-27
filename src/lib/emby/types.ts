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
  | "notSignedIn"
  | "sessionExpired"
  | "credentialStore";

export interface EmbyError {
  kind: EmbyErrorKind;
  message: string;
}
