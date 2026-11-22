// The single seam for every frontend <-> native call. The build picks the
// backend: `npm run build:wails` (Vite mode "wails") sets VITE_NATIVE to
// "wails", every other build uses Tauri. vite.config.ts defines the value as
// a literal, so the unused backend is dropped from the bundle.
import * as tauri from "./tauri";
import type { Native } from "./types";
import * as wails from "./wails";

const impl: Native = import.meta.env.VITE_NATIVE === "wails" ? wails : tauri;

export const invoke: Native["invoke"] = impl.invoke;
export const listen: Native["listen"] = impl.listen;
export const window: Native["window"] = impl.window;
export type { Native, Unlisten } from "./types";
