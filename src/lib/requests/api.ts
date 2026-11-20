import { invoke } from "@tauri-apps/api/core";
import type {
  AuthSession,
  BrowseFilters,
  DiscoverResponse,
  Genre,
  MediaDetails,
  MediaSearchResults,
  MediaType,
  NewRequest,
  PagedMedia,
  RequestDetail,
  RequestPage,
  RequestQuota,
  RequestStatus,
  RequestsError,
  UrStatus,
} from "./types";

// Thin typed wrapper over the Request commands of
// src-tauri/src/request/commands.rs. Every call goes through Rust (ADR 0125).
export const requestsApi = {
  status: () => invoke<UrStatus>("ur_status"),
  setAddress: (address: string) =>
    invoke<UrStatus>("ur_set_address", { address }),
  signIn: () => invoke<UrStatus>("ur_sign_in"),
  signOut: () => invoke<void>("ur_sign_out"),
  discover: () => invoke<DiscoverResponse>("ur_discover"),
  browse: (mediaType: MediaType, filters: BrowseFilters) =>
    invoke<PagedMedia>("ur_browse", { mediaType, filters }),
  genres: (mediaType: MediaType) => invoke<Genre[]>("ur_genres", { mediaType }),
  search: (query: string, page?: number) =>
    invoke<MediaSearchResults>("ur_search", { query, page }),
  media: (mediaType: MediaType, tmdbId: number) =>
    invoke<MediaDetails>("ur_media", { mediaType, tmdbId }),
  quota: () => invoke<RequestQuota>("ur_quota"),
  request: (request: NewRequest) =>
    invoke<RequestDetail>("ur_request", { request }),
  myRequests: (status: RequestStatus[] | null, page: number) =>
    invoke<RequestPage>("ur_my_requests", { status, page }),
  requestDetail: (id: number) =>
    invoke<RequestDetail>("ur_request_detail", { id }),
  cancel: (id: number) => invoke<RequestDetail>("ur_cancel", { id }),
  sessions: () => invoke<AuthSession[]>("ur_sessions"),
  revokeSession: (id: string) => invoke<void>("ur_revoke_session", { id }),
  /** « Bande-annonce »: the YouTube page of the key, in the default browser (ADR 0127). */
  openTrailer: (key: string) => invoke<void>("ur_open_trailer", { key }),
};

/** The `{ kind, message }` of a failed command; the server's text is kept as is. */
export function toRequestsError(e: unknown): RequestsError {
  if (
    typeof e === "object" &&
    e !== null &&
    "kind" in e &&
    typeof e.kind === "string" &&
    "message" in e &&
    typeof e.message === "string"
  ) {
    return { kind: e.kind, message: e.message };
  }
  return {
    kind: "invalidResponse",
    message: typeof e === "string" ? e : "erreur inattendue",
  };
}
