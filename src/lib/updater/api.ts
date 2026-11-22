import { invoke, listen, type Unlisten } from "../native";

// Thin typed wrapper over the update commands of src-tauri/src/updater.rs.

export const UPDATE_READY_EVENT = "app://update-ready";

/** A downloaded, verified update waiting to be installed. */
export interface UpdateInfo {
  version: string;
  /** Markdown release notes, empty when there are none. */
  notes: string;
}

/** Result of a manual check (`CheckOutcome` in updater.rs). */
export type CheckOutcome =
  | { status: "upToDate" }
  | { status: "ready"; info: UpdateInfo }
  /** Debug builds: a newer version exists but is not downloaded. */
  | { status: "available"; version: string }
  /** `category`: network, signature, manifest, transport, io or other. */
  | { status: "failed"; category: string };

export interface UpdaterApi {
  pending: () => Promise<UpdateInfo | null>;
  /** Runs the installer; on success the app exits and is relaunched. */
  install: () => Promise<void>;
  onReady: (handler: (info: UpdateInfo) => void) => Promise<Unlisten>;
  /** Checks now and, in release builds, downloads a newer version. */
  check: () => Promise<CheckOutcome>;
}

export const updaterApi: UpdaterApi = {
  pending: () => invoke<UpdateInfo | null>("app_update_pending"),
  install: () => invoke<void>("app_update_install"),
  onReady: (handler) => listen<UpdateInfo>(UPDATE_READY_EVENT, handler),
  check: () => invoke<CheckOutcome>("app_update_check"),
};
