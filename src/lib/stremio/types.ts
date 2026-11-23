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
