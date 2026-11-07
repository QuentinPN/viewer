import { invoke } from "@tauri-apps/api/core";

// Typed wrapper over the diagnostics command of src-tauri/src/diagnostics.rs (ADR 0033).

export const diagnosticsApi = {
  /** Opens the log folder in the file explorer; the folder is chosen by Rust. */
  openLogDir: () => invoke<void>("diagnostics_open_log_dir"),
};

/** Message shown when the folder could not be opened. */
export function openLogDirError(e: unknown): string {
  const reason =
    typeof e === "string" && e.trim() !== "" ? e.trim() : "erreur inattendue";
  return `Impossible d'ouvrir le dossier des journaux : ${reason}.`;
}
