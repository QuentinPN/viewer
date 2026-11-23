import { invoke } from "../native";
import { toRequestsError } from "../requests/api";
import type { StremioError, StremioStatus } from "./types";

// Thin typed wrapper over the Stremio commands of
// go-app/internal/stremio/commands.go. Every call goes through Go; the
// password is only passed through, never kept here.
export const stremioApi = {
  status: () => invoke<StremioStatus>("stremio_status"),
  signIn: (email: string, password: string) =>
    invoke<StremioStatus>("stremio_sign_in", { email, password }),
  signOut: () => invoke<StremioStatus>("stremio_sign_out"),
};

/** The `{ kind, message }` of a failed command; Go's French text is kept as is. */
export function toStremioError(e: unknown): StremioError {
  return toRequestsError(e);
}
