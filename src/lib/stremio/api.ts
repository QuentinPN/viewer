import type { PlaybackStart } from "../emby/types";
import { invoke } from "../native";
import { toRequestsError } from "../requests/api";
import type {
  AddonStreams,
  SourcesTarget,
  StremioError,
  StremioSources,
  StremioState,
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
  /** A new query for an Emby item or an outside title, and the addons to ask. */
  sources: (target: SourcesTarget) =>
    invoke<StremioSources>("stremio_sources", target),
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

/**
 * The account state for the buttons of a page, read without waiting on it;
 * null when Go could not tell, so nothing Stremio is offered.
 */
export function stremioState(): Promise<StremioState | null> {
  return stremioApi.status().then(
    (s) => s.state,
    () => null,
  );
}
