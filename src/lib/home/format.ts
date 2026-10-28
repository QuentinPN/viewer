// Pure French labels of the home cards; unit tested in format.test.ts.
import type { WatchItem } from "../emby/types";
import { formatMinutes } from "../format/duration";

/** `S5 · É2`; `null` without numbers. */
export function episodeCode(item: WatchItem): string | null {
  if (item.kind !== "episode") return null;
  const season = item.seasonIndex === 0 ? "Spécial" : item.seasonIndex;
  if (season == null && item.index == null) return null;
  if (season === "Spécial")
    return item.index == null ? "Spécial" : `Spécial ${item.index}`;
  return [
    season != null ? `S${season}` : null,
    item.index != null ? `É${item.index}` : null,
  ]
    .filter(Boolean)
    .join(" · ");
}

/** Card title: the series for an episode, else the film. */
export function watchTitle(item: WatchItem): string {
  return item.kind === "episode" && item.seriesName
    ? item.seriesName
    : item.name;
}

/** Remaining time of a started item: `Reste 1 h 12`, `Reste 8 min`. */
export function remainingLabel(item: WatchItem): string | null {
  if (!item.resume || item.runtimeMinutes == null) return null;
  const left = formatMinutes(
    item.runtimeMinutes - item.resume.positionSeconds / 60,
  );
  return left ? `Reste ${left}` : null;
}

/** Second line: `S5 · É2 · Le Professeur`, then the remaining time or runtime. */
export function watchMeta(item: WatchItem): string {
  const time = remainingLabel(item) ?? formatMinutes(item.runtimeMinutes);
  const parts =
    item.kind === "episode"
      ? [episodeCode(item), item.seriesName ? item.name : null]
      : [];
  return [...parts, time].filter(Boolean).join(" · ");
}

/** What the card does, for screen readers: it plays at once. */
export function watchAction(item: WatchItem): string {
  const what =
    item.kind === "episode" && item.seriesName
      ? `${item.seriesName}, ${spokenEpisode(item)}${item.name}`
      : item.name;
  return item.resume ? `Reprendre ${what}` : `Lire ${what}`;
}

function spokenEpisode(item: WatchItem): string {
  if (item.seasonIndex === 0)
    return item.index == null
      ? "épisode spécial, "
      : `épisode spécial ${item.index}, `;
  const parts = [
    item.seasonIndex != null ? `saison ${item.seasonIndex}` : null,
    item.index != null ? `épisode ${item.index}` : null,
  ].filter(Boolean);
  return parts.length ? `${parts.join(" ")}, ` : "";
}

/** Next-up episodes already in "Reprendre" are not shown twice. */
export function withoutDuplicates(
  nextUp: WatchItem[],
  inProgress: WatchItem[],
): WatchItem[] {
  const seen = new Set(inProgress.map((i) => i.id));
  return nextUp.filter((i) => !seen.has(i.id));
}
