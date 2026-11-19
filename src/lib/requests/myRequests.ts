// Segments of « Mes demandes » (Mes demandes A): the statuses each one asks
// `GET /api/requests?status=a,b` for, and its count from `RequestPage.totals`.
import type { RequestStatus, RequestTotals } from "./types";

export type SegmentKey =
  | "all"
  | "pending"
  | "inProgress"
  | "available"
  | "declined";

export interface Segment {
  key: SegmentKey;
  label: string;
  /** null: every status. */
  statuses: RequestStatus[] | null;
  /** Shown when the segment has no request. */
  empty: string;
}

export const SEGMENTS: Segment[] = [
  {
    key: "all",
    label: "Toutes",
    statuses: null,
    empty: "Tu n'as encore rien demandé.",
  },
  {
    key: "pending",
    label: "En attente",
    statuses: ["pending"],
    empty: "Aucune demande en attente.",
  },
  {
    key: "inProgress",
    label: "En cours",
    statuses: ["approved", "sent"],
    empty: "Aucune demande en cours.",
  },
  {
    key: "available",
    label: "Disponibles",
    statuses: ["available"],
    empty: "Aucune demande disponible pour le moment.",
  },
  {
    key: "declined",
    label: "Sans suite",
    statuses: ["declined", "failed", "cancelled"],
    empty: "Aucune demande refusée, annulée ou en échec.",
  },
];

export function segmentCount(totals: RequestTotals, s: Segment): number {
  if (s.statuses === null) return totalCount(totals);
  return s.statuses.reduce((n, status) => n + totals[status], 0);
}

/** Every request, whatever its status. */
export function totalCount(totals: RequestTotals): number {
  return Object.values(totals).reduce((n, v) => n + v, 0);
}

/** « Mes demandes · N »: waiting, approved or sent. */
export function openCount(totals: RequestTotals): number {
  return totals.pending + totals.approved + totals.sent;
}
