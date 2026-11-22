// « Format de l'image »: how the picture fills the window, as on the TV.

export type VideoFit = "original" | "fill" | "stretch";

/** In the options' order; the badge shows in the controls, none for « Original ». */
export const FITS: readonly {
  fit: VideoFit;
  label: string;
  badge: string | null;
}[] = [
  { fit: "original", label: "Original", badge: null },
  { fit: "fill", label: "Remplir la fenêtre", badge: "Remplir" },
  { fit: "stretch", label: "Étirer", badge: "Étiré" },
];

export function fitBadge(fit: VideoFit): string | null {
  return FITS.find((f) => f.fit === fit)?.badge ?? null;
}
