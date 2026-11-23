// Shapes of the Stremio bridge commands (go-app/internal/stremio/commands.go).
import type { RequestsError } from "../requests/types";

export type StremioState =
  | "signed_out"
  | "signed_in"
  | "reauth_required"
  | "unreachable";

/** An addon of the account that serves streams, and for which kinds. */
export interface AddonInfo {
  name: string;
  movie: boolean;
  series: boolean;
}

/** Answer of `stremio_status`, `stremio_sign_in` and `stremio_sign_out`. */
export interface StremioStatus {
  state: StremioState;
  /** Go may send `null` for an empty list. */
  addons: AddonInfo[] | null;
}

/** The `{ kind, message }` of a failed command: same bridge shape as `ur_*`. */
export type StremioError = RequestsError;

/** An addon asked for streams of a title, in the account's collection order. */
export interface SourceAddon {
  id: string;
  name: string;
}

/**
 * A title outside Emby, by its IMDb id: a film without `season` and
 * `episode`, an episode with both (`name` is then the series').
 */
export interface ExternalTarget {
  imdb: string;
  name: string;
  season?: number;
  episode?: number;
}

/** What `stremio_sources` lists the streams of: an Emby item or an outside title. */
export type SourcesTarget = { itemId: string } | { external: ExternalTarget };

/** Answer of `stremio_sources`: the query the streams are asked under. */
export interface StremioSources {
  /**
   * The title's key, the `itemId` of the stream played from it: the Emby item
   * id, or « stremio:… » for a title outside Emby.
   */
  key: string;
  query: string;
  /** Go may send `null` for an empty list. */
  addons: SourceAddon[] | null;
}

/** A playable stream: an opaque id, never its URL or headers. */
export interface StreamDescriptor {
  id: string;
  /** May hold several lines. */
  name: string;
  /** The addon's `description` or `title`, lines kept. */
  description: string;
  /** In bytes; null when the addon gave none. */
  size: number | null;
}

/** Answer of `stremio_addon_streams` for one addon. */
export interface AddonStreams {
  /** Go may send `null` for an empty list. */
  streams: StreamDescriptor[] | null;
  /** Streams left out: torrents without a link and the like. */
  unsupported: number;
}
