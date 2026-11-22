// The single seam for every frontend <-> native call. Only the Tauri
// implementation exists today; a build flag will pick the backend here later.
export { invoke, listen, window } from "./tauri";
export type { Unlisten } from "./types";
