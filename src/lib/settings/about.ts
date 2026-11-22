import { invoke } from "../native";

// Réglages › À propos and Serveur: what the app, this PC and the server
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

export type Codec = "h264" | "hevc" | "hevc10" | "vp9" | "vp9Profile2" | "av1";

export interface DisplayInfo {
  name: string;
  hdr: boolean;
}

export interface SystemReport {
  debugBuild: boolean;
  os: OsInfo | null;
  gpus: GpuInfo[];
  /** D3D11 Video profiles of the first GPU; `null` when they could not be read. */
  decoders: Codec[] | null;
  displays: DisplayInfo[];
}

/** The codecs shown, in this order, whether decoded by the GPU or not. */
export const CODECS: readonly { codec: Codec; label: string }[] = [
  { codec: "h264", label: "H.264" },
  { codec: "hevc", label: "HEVC" },
  { codec: "hevc10", label: "HEVC 10 bits" },
  { codec: "vp9", label: "VP9" },
  { codec: "vp9Profile2", label: "VP9 10 bits" },
  { codec: "av1", label: "AV1" },
];

/**
 * One row per codec: "Matériel" when the GPU's driver lists it, else "Logiciel"
 * (mpv still decodes it, on the processor). "Inconnu" when nothing was read.
 */
export function decoderRows(decoders: Codec[] | null): InfoRow[] {
  return CODECS.map(({ codec, label }) => ({
    label,
    value:
      decoders === null
        ? UNKNOWN
        : decoders.includes(codec)
          ? "Matériel"
          : "Logiciel",
  }));
}

/** "\\.\DISPLAY2" is "Écran 2", as Windows' display settings number it. */
export function displayName(name: string, index: number): string {
  const n = /DISPLAY(\d+)$/i.exec(name)?.[1];
  return `Écran ${n ?? index + 1}`;
}

export function hdrRows(displays: DisplayInfo[]): InfoRow[] {
  if (displays.length === 0) return [{ label: "HDR", value: UNKNOWN }];
  return displays.map((d, i) => ({
    label: `HDR · ${displayName(d.name, i)}`,
    value: d.hdr ? "Activé" : "Désactivé dans Windows",
  }));
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
