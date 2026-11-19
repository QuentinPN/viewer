// Request models as the Tauri commands return them (src-tauri/src/request/
// models.rs), field for field from contracts/request/openapi.json (ADR 0125).
// Images come ready from Rust on the `requestimg` protocol: never build a URL here.
import type { ImageRef } from "../emby/types";

export type MediaType = "movie" | "tv";

export type MediaStatus =
  | "available"
  | "partial"
  | "downloading"
  | "requested"
  | "pending"
  | "declined";

export type RequestStatus =
  | "pending"
  | "approved"
  | "declined"
  | "cancelled"
  | "sent"
  | "failed"
  | "available";

export type Role = "member" | "auto_approve" | "manager" | "owner";

export type SortOrder = "popularity" | "rating" | "release" | "title";

export type EventType =
  | "requested"
  | "approved"
  | "declined"
  | "cancelled"
  | "sent"
  | "failed"
  | "retried"
  | "available";

export interface RequestsError {
  kind: string;
  message: string;
}

export interface Capabilities {
  canRequestMovies: boolean;
  canRequestTv: boolean;
  canRequest4k: boolean;
  autoApproveMovies: boolean;
  autoApproveTv: boolean;
  autoApprove4k: boolean;
  canRequestOnBehalf: boolean;
  canRequestQuotaExempt: boolean;
  canAdvancedRequest: boolean;
  canAutoRequestWatchlist: boolean;
  canManageRequests: boolean;
  canManageIssues: boolean;
  canManageBlocklist: boolean;
  canViewStats: boolean;
  canManageUsers: boolean;
  canRequest: boolean;
  isOwner: boolean;
  quotaExempt: boolean;
}

export interface Viewer {
  id: string;
  name: string;
  role: Role;
  capabilities: Capabilities;
  avatarUrl: string | null;
}

export type UrState =
  | "notConfigured"
  | "signedOut"
  | "tooOld"
  | "unreachable"
  | "rejected"
  | "ready";

/** State of the « Demander » tab (`ur_status`). */
export interface UrStatus {
  state: UrState;
  address: string | null;
  viewer: Viewer | null;
  apiVersion: string | null;
  missing: string[];
  message: string | null;
}

export interface AuthSession {
  id: string;
  deviceName: string | null;
  userAgent: string | null;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  current: boolean;
}

export interface Genre {
  id: number;
  name: string;
}

export type Blocked = { by: "title" } | { by: "keyword"; keyword: string };

export interface MediaSummary {
  tmdbId: number;
  type: MediaType;
  title: string;
  year: number | null;
  posterPath: string | null;
  backdropPath: string | null;
  overview: string;
  blocked?: Blocked | null;
  status?: MediaStatus | null;
  progress?: number | null;
  embyItemId?: string | null;
  poster: ImageRef | null;
  backdrop: ImageRef | null;
}

export interface PagedMedia {
  page: number;
  totalPages: number;
  results: MediaSummary[];
}

/** Filters of a Découvrir row's `seeAll`, also those of « Parcourir ». */
export interface SeeAllFilters {
  genres?: number[] | null;
  yearFrom?: number | null;
  yearTo?: number | null;
  rating?: number | null;
  runtimeMin?: number | null;
  runtimeMax?: number | null;
  language?: string | null;
  provider?: number | null;
  sort?: SortOrder | null;
  company?: number | null;
  network?: number | null;
}

/** Argument `filters` of `ur_browse`. */
export interface BrowseFilters extends SeeAllFilters {
  page?: number;
}

export interface SeeAll {
  type: MediaType;
  filters: SeeAllFilters;
}

export interface DiscoverRow {
  id: string;
  title: string;
  items: MediaSummary[];
  seeAll?: SeeAll | null;
}

export interface DiscoverResponse {
  rows: DiscoverRow[];
  layout: string[];
}

export interface SearchPerson {
  id: number;
  name: string;
  profilePath: string | null;
  profile: ImageRef | null;
  knownFor: string[];
}

export interface MediaSearchResults {
  page: number;
  totalPages: number;
  results: MediaSummary[];
  people: SearchPerson[];
}

export interface EpisodeCount {
  present: number;
  expected: number;
}

export interface FourK {
  status?: MediaStatus | null;
  progress?: number | null;
  episodes?: EpisodeCount | null;
}

export interface Season {
  seasonNumber: number;
  episodeCount: number;
  airDate: string | null;
  episodes?: EpisodeCount | null;
  fourK?: FourK | null;
  status?: MediaStatus | null;
  progress?: number | null;
}

export interface CastMember {
  id: number;
  name: string;
  character: string;
  profilePath: string | null;
  profile: ImageRef | null;
}

export interface CrewMember {
  id: number;
  name: string;
  jobs: ("director" | "writer" | "creator")[];
  profilePath: string | null;
  profile: ImageRef | null;
}

export interface Collection {
  id: number;
  name: string;
  posterPath: string | null;
  backdropPath: string | null;
  poster: ImageRef | null;
  backdrop: ImageRef | null;
}

export interface WatchProvider {
  id: number;
  name: string;
  logoPath: string | null;
}

export interface MediaDetails extends MediaSummary {
  genres: Genre[];
  runtime: number | null;
  seasons: Season[];
  cast: CastMember[];
  crew?: CrewMember[] | null;
  collection?: Collection | null;
  trailerKey: string | null;
  watchProviders: {
    flatrate: WatchProvider[];
    rent: WatchProvider[];
    buy: WatchProvider[];
  };
  recommendations: MediaSummary[];
  similar: MediaSummary[];
  externalIds: { imdb: string | null; tvdb: number | null };
  fourK?: FourK | null;
  keywords?: Genre[] | null;
  originalLanguage?: string | null;
  lastAired?: { seasonNumber: number; episodeNumber: number } | null;
}

export interface QuotaUsage {
  limit: number;
  days: number;
  used: number;
  remaining: number;
}

export interface RequestQuota {
  movies: QuotaUsage;
  seasons: QuotaUsage;
  exempt: boolean;
}

export interface RequestPerson {
  id: string;
  name: string;
  avatarUrl: string | null;
}

export interface RequestView {
  id: number;
  type: MediaType;
  tmdbId: number;
  status: RequestStatus;
  seasons: number[];
  requester: RequestPerson;
  createdBy: RequestPerson | null;
  quotaExempt: boolean;
  decidedBy: RequestPerson | null;
  declineReason: string | null;
  qualityProfileId: number | null;
  rootFolder: string | null;
  seriesType: "standard" | "anime" | "daily" | null;
  is4k: boolean;
  server: { id: number; name: string } | null;
  createdAt: string;
  updatedAt: string;
  media: MediaSummary | null;
  failureReason: string | null;
}

export interface RequestEvent {
  id: number;
  type: EventType;
  actor: RequestPerson | null;
  detail: string | null;
  createdAt: string;
}

export interface RequestDetail extends RequestView {
  events: RequestEvent[];
}

export interface RequestTotals {
  pending: number;
  approved: number;
  declined: number;
  cancelled: number;
  sent: number;
  failed: number;
  available: number;
}

export interface RequestPage {
  page: number;
  totalPages: number;
  results: RequestView[];
  totals: RequestTotals;
}

/** Body of `ur_request`: member fields only. */
export interface NewRequest {
  type: MediaType;
  tmdbId: number;
  seasons?: "all" | number[];
}
