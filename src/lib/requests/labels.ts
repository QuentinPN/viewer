// French labels and tones of Request statuses (spec « Décisions d'UX »).
import type {
  AuthSession,
  EventType,
  MediaStatus,
  QuotaUsage,
  RequestQuota,
  RequestStatus,
  Role,
  UrStatus,
} from "./types";

export type Tone = "success" | "accent" | "warning" | "danger" | "muted";

const MEDIA_LABELS: Record<MediaStatus, string> = {
  available: "Disponible",
  partial: "Partiel",
  downloading: "Téléchargement",
  requested: "Demandé",
  pending: "En attente",
  declined: "Refusé",
};

const REQUEST_LABELS: Record<RequestStatus, string> = {
  pending: "En attente",
  approved: "Approuvée",
  sent: "Envoyée",
  available: "Disponible",
  declined: "Refusée",
  cancelled: "Annulée",
  failed: "Échec",
};

const TONES: Record<MediaStatus | RequestStatus, Tone> = {
  available: "success",
  partial: "accent",
  downloading: "accent",
  requested: "accent",
  approved: "accent",
  sent: "accent",
  pending: "warning",
  declined: "danger",
  failed: "danger",
  cancelled: "muted",
};

export function statusLabel(status: MediaStatus): string {
  return MEDIA_LABELS[status];
}

export function requestStatusLabel(status: RequestStatus): string {
  return REQUEST_LABELS[status];
}

const EVENT_LABELS: Record<EventType, string> = {
  requested: "Demandée",
  approved: "Approuvée",
  declined: "Refusée",
  cancelled: "Annulée",
  sent: "Envoyée",
  failed: "Échec",
  retried: "Nouvel essai",
  available: "Disponible",
};

/** Step of a request's timeline; a type this version does not know reads as is. */
export function eventLabel(type: string): string {
  return EVENT_LABELS[type as EventType] ?? type;
}

const DAY_MS = 24 * 60 * 60 * 1000;
const longDate = new Intl.DateTimeFormat("fr-FR", { dateStyle: "long" });
const dateTime = new Intl.DateTimeFormat("fr-FR", {
  dateStyle: "long",
  timeStyle: "short",
});

/** « aujourd'hui », « hier », « il y a 2 jours », then the date after 30 days. */
export function relativeDay(iso: string, now: number): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return iso;
  const days = Math.floor((startOfDay(now) - startOfDay(then)) / DAY_MS);
  if (days <= 0) return "aujourd'hui";
  if (days === 1) return "hier";
  if (days <= 30) return `il y a ${days} jours`;
  return `le ${longDate.format(then)}`;
}

function startOfDay(t: number): number {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** « 19 novembre 2026 à 10:41 » for a step of the timeline. */
export function eventDate(iso: string): string {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? iso : dateTime.format(t);
}

export function typeLabel(type: "movie" | "tv"): string {
  return type === "movie" ? "Film" : "Série";
}

export function statusTone(status: MediaStatus | RequestStatus): Tone {
  return TONES[status];
}

/** « 60 % » from a 0–1 progress; null when unknown. */
export function percent(progress: number | null | undefined): string | null {
  if (progress === null || progress === undefined || !(progress >= 0))
    return null;
  return `${Math.round(Math.min(progress, 1) * 100)} %`;
}

/** Badge text of a title or season: the label, with the progress while downloading. */
export function mediaStatusText(
  status: MediaStatus,
  progress?: number | null,
): string {
  const p = status === "downloading" ? percent(progress) : null;
  return p ? `${statusLabel(status)} · ${p}` : statusLabel(status);
}

const ROLES: Record<Role, string> = {
  member: "Membre",
  auto_approve: "Approbation automatique",
  manager: "Gestionnaire",
  owner: "Propriétaire",
};

export function roleLabel(role: Role): string {
  return ROLES[role];
}

function plural(n: number, word: string): string {
  return `${n} ${word}${Math.abs(n) > 1 ? "s" : ""}`;
}

/** « 3 films sur 5 », then « restants » / « restantes » when asked. */
function usage(u: QuotaUsage, word: string, left = ""): string {
  const tail = left ? ` ${left}${u.remaining > 1 ? "s" : ""}` : "";
  return `${plural(u.remaining, word)} sur ${u.limit}${tail}`;
}

function period(days: number): string {
  return `sur ${plural(days, "jour")}`;
}

/**
 * « Quota : 3 films sur 5 restants · 8 saisons sur 10 · sur 7 jours »;
 * null when the member is exempt.
 */
export function quotaLine(q: RequestQuota): string | null {
  if (q.exempt) return null;
  const movies = usage(q.movies, "film", "restant");
  const seasons = usage(q.seasons, "saison");
  if (q.movies.days === q.seasons.days) {
    return `Quota : ${movies} · ${seasons} · ${period(q.movies.days)}`;
  }
  return `Quota : ${movies} ${period(q.movies.days)} · ${seasons} ${period(q.seasons.days)}`;
}

/** Quota of one kind in the request dialog: « 8 saisons sur 10 restantes · sur 7 jours ». */
export function quotaUsageLine(q: RequestQuota, kind: "movie" | "tv"): string {
  const u = kind === "movie" ? q.movies : q.seasons;
  const text =
    kind === "movie"
      ? usage(u, "film", "restant")
      : usage(u, "saison", "restante");
  return `${text} · ${period(u.days)}`;
}

/** Name of a connected device: its name, else the browser or app that opened it. */
export function sessionName(s: AuthSession): string {
  return s.deviceName ?? s.userAgent ?? "Appareil inconnu";
}

/** The address is plain HTTP: allowed on the local network only, not encrypted. */
export function isPlainHttp(address: string | null): boolean {
  return address?.trim().toLowerCase().startsWith("http://") ?? false;
}

/** Line under the address in Réglages › Demandes, with the tone of its dot. */
export function stateLine(s: UrStatus): { text: string; tone: Tone } {
  switch (s.state) {
    case "ready": {
      const parts = s.viewer
        ? [`Connecté en tant que ${s.viewer.name}`, roleLabel(s.viewer.role)]
        : ["Connecté"];
      if (s.apiVersion) parts.push(`API ${s.apiVersion}`);
      return { text: parts.join(" · "), tone: "success" };
    }
    case "signedOut":
      return { text: "Déconnecté", tone: "muted" };
    case "notConfigured":
      return { text: "Aucune adresse enregistrée", tone: "muted" };
    case "tooOld":
      return { text: "Serveur Request trop ancien", tone: "warning" };
    case "unreachable":
      return { text: "Request est injoignable", tone: "danger" };
    case "rejected":
      return { text: "Connexion refusée", tone: "danger" };
  }
}
