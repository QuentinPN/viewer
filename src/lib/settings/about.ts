import { invoke } from "@tauri-apps/api/core";

// Réglages › À propos and Serveur (ADR 0116): what the app, this PC and the server
// say about themselves. Typed wrapper over `system_report` and the labels shown.

export interface OsInfo {
  edition: string;
  displayVersion: string | null;
  build: string;
}

export interface GpuInfo {
  name: string;
  driver: string | null;
}

export interface SystemReport {
  debugBuild: boolean;
  os: OsInfo | null;
  gpus: GpuInfo[];
}

export const systemApi = {
  report: () => invoke<SystemReport>("system_report"),
};

export interface InfoRow {
  label: string;
  value: string;
}

export const UNKNOWN = "Inconnu";

export function buildType(debugBuild: boolean): string {
  return debugBuild ? "Débogage" : "Publication";
}

/** "Windows 11 Pro 23H2 (build 22631.4460)". */
export function osLabel(os: OsInfo | null): string {
  if (!os) return UNKNOWN;
  const version = os.displayVersion ? ` ${os.displayVersion}` : "";
  return `${os.edition}${version} (build ${os.build})`;
}

/** One row per graphics adapter; "Inconnu" when none was found. */
export function gpuRows(gpus: GpuInfo[]): InfoRow[] {
  if (gpus.length === 0) return [{ label: "Carte graphique", value: UNKNOWN }];
  return gpus.map((g, i) => ({
    label: gpus.length > 1 ? `Carte graphique ${i + 1}` : "Carte graphique",
    value: g.driver ? `${g.name} · pilote ${g.driver}` : g.name,
  }));
}

export function aboutRows(
  version: string | null,
  report: SystemReport | null,
): InfoRow[] {
  return [
    { label: "Version", value: version ?? UNKNOWN },
    {
      label: "Compilation",
      value: report ? buildType(report.debugBuild) : UNKNOWN,
    },
    { label: "Système", value: osLabel(report?.os ?? null) },
    ...gpuRows(report?.gpus ?? []),
  ];
}
