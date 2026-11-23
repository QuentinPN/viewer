import type { PlaybackStart } from "../emby/types";
import { invoke } from "../native";
import { toRequestsError } from "../requests/api";
import type {
  AddonStreams,
  StremioError,
  StremioSources,
  StremioStatus,
} from "./types";

// Thin typed wrapper over the Stremio commands of
// go-app/internal/stremio/commands.go. Every call goes through Go; the
// password is only passed through, never kept here. Streams are opaque ids:
// Go keeps their URLs and headers.
export const stremioApi = {
  status: () => invoke<StremioStatus>("stremio_status"),
  signIn: (email: string, password: string) =>
    invoke<StremioStatus>("stremio_sign_in", { email, password }),
  signOut: () => invoke<StremioStatus>("stremio_sign_out"),
  /** A new query for an Emby film or episode, and the addons to ask. */
  sources: (itemId: string) =>
    invoke<StremioSources>("stremio_sources", { itemId }),
  addonStreams: (query: string, addonId: string) =>
    invoke<AddonStreams>("stremio_addon_streams", { query, addonId }),
  /** Loads the stream in the player, with no Emby report. */
  play: (streamId: string) =>
    invoke<PlaybackStart>("stremio_play", { streamId }),
};

/** The `{ kind, message }` of a failed command; Go's French text is kept as is. */
export function toStremioError(e: unknown): StremioError {
  return toRequestsError(e);
}
