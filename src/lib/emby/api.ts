import { invoke } from "@tauri-apps/api/core";
import type {
  EmbyError,
  EpisodePage,
  ItemDetail,
  ItemPage,
  Library,
  Season,
  ServerSummary,
  SignedInUser,
} from "./types";

// Thin typed wrapper over the Emby commands of src-tauri/src/commands.rs.

export const embyApi = {
  probe: (url: string) => invoke<ServerSummary>("emby_probe", { url }),
  signIn: (url: string, username: string, password: string) =>
    invoke<SignedInUser>("emby_sign_in", { url, username, password }),
  restore: () => invoke<SignedInUser | null>("emby_restore"),
  signOut: () => invoke<void>("emby_sign_out"),
  lastServer: () => invoke<string | null>("emby_last_server"),
  libraries: () => invoke<Library[]>("emby_libraries"),
  items: (libraryId: string, startIndex: number, limit: number) =>
    invoke<ItemPage>("emby_items", { libraryId, startIndex, limit }),
  itemDetail: (itemId: string) =>
    invoke<ItemDetail>("emby_item_detail", { itemId }),
  seasons: (seriesId: string) => invoke<Season[]>("emby_seasons", { seriesId }),
  episodes: (
    seriesId: string,
    seasonId: string,
    startIndex: number,
    limit: number,
  ) =>
    invoke<EpisodePage>("emby_episodes", {
      seriesId,
      seasonId,
      startIndex,
      limit,
    }),
};

/** Normalizes a rejected command into an `EmbyError`, whatever was thrown. */
export function toEmbyError(e: unknown): EmbyError {
  if (
    typeof e === "object" &&
    e !== null &&
    "kind" in e &&
    "message" in e &&
    typeof e.message === "string"
  ) {
    return e as EmbyError;
  }
  return {
    kind: "invalidResponse",
    message: typeof e === "string" ? e : "erreur inattendue",
  };
}

/** One-line hint telling the user what to do next, in French. */
export function errorHint(error: EmbyError): string | null {
  switch (error.kind) {
    case "unreachable":
    case "timeout":
      return "Vérifiez l'adresse, le port (8096 par défaut) et que le serveur est allumé.";
    case "insecureRemoteUrl":
      return "Utilisez une adresse https://, ou l'adresse locale du serveur.";
    case "invalidCredentials":
      return "Le nom d'utilisateur et le mot de passe sont sensibles à la casse.";
    case "sessionExpired":
      return "Votre session a été fermée sur le serveur. Reconnectez-vous.";
    case "invalidResponse":
      return "L'adresse répond, mais ce n'est peut-être pas un serveur Emby.";
    case "notFound":
      return "Elle a peut-être été supprimée ou masquée. Actualisez la liste.";
    case "credentialStore":
      return "Le Gestionnaire d'identification Windows n'a pas pu enregistrer la session.";
    default:
      return null;
  }
}
