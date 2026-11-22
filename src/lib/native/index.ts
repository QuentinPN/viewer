// The single seam for every frontend <-> native call: the Go + Wails app
// (go-app/), through its runtime (./wails.ts).
import type { Native } from "./types";
import * as wails from "./wails";

export const invoke: Native["invoke"] = wails.invoke;
export const listen: Native["listen"] = wails.listen;
export const window: Native["window"] = wails.window;
export type { Native, Unlisten } from "./types";
