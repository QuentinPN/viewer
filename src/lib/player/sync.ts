import { invoke, listen, type Unlisten } from "../native";

// Linked playback with a TV. Rust leads the link; the view shows its
// status and notices and carries out the few actions it asks for. Only session
// ids and device names reach the UI.

/** A Viewer TV open on the account. */
export interface SyncDevice {
  sessionId: string;
  name: string;
}

export type SyncStatus =
  | { phase: "idle" }
  | { phase: "waiting"; device: string }
  | { phase: "linked"; device: string; driftMs: number | null };

export type SyncNotice =
  | { kind: "following"; device: string }
  | { kind: "lost"; device: string }
  | { kind: "noAnswer"; device: string }
  | { kind: "busy"; device: string };

/** What the TV asked that only the view can do. */
export type SyncAction = "next" | "previous" | "stop";

export const SYNC_EMPTY = "Aucune TV Viewer n'est ouverte.";

export const syncApi = {
  devices: () => invoke<SyncDevice[]>("sync_devices"),
  invite: (sessionId: string) => invoke<void>("sync_invite", { sessionId }),
  unlink: () => invoke<void>("sync_unlink"),
  status: () => invoke<SyncStatus>("sync_status"),
  onStatus: (handler: (s: SyncStatus) => void): Promise<Unlisten> =>
    listen<SyncStatus>("sync://status", handler),
  onNotice: (handler: (n: SyncNotice) => void): Promise<Unlisten> =>
    listen<SyncNotice>("sync://notice", handler),
  onAction: (handler: (a: SyncAction) => void): Promise<Unlisten> =>
    listen<SyncAction>("sync://action", handler),
};

/** « Lié à Shield · ±40 ms », the gap once the TV measured one. */
export function pillText(device: string, driftMs: number | null): string {
  return driftMs === null
    ? `Lié à ${device}`
    : `Lié à ${device} · ±${Math.round(Math.abs(driftMs))} ms`;
}

export function noticeText(notice: SyncNotice): string {
  switch (notice.kind) {
    case "following":
      return `${notice.device} suit ta lecture.`;
    case "lost":
      return `Lien perdu avec ${notice.device}. La lecture continue ici.`;
    case "noAnswer":
      return `${notice.device} ne répond pas.`;
    case "busy":
      return `${notice.device} suit déjà une autre lecture.`;
  }
}
