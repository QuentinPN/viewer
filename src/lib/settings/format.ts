import { formatSize } from "../detail/format";
import type { ArtworkCacheCleared, ArtworkCacheStats } from "../emby/types";

const integer = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 0 });

/** `0 o` instead of nothing: an empty cache still has a size. */
function size(bytes: number): string {
  return formatSize(bytes) ?? "0 o";
}

/** `aucune image`, `1 image`, `2 431 images`. */
export function imageCount(n: number): string {
  if (n <= 0) return "aucune image";
  return `${integer.format(n)} ${n === 1 ? "image" : "images"}`;
}

/** `123 Mo sur 512 Mo`. */
export function cacheUsage(stats: ArtworkCacheStats): string {
  return `${size(stats.bytes)} sur ${size(stats.quotaBytes)}`;
}

/** Share of the quota in use, between 0 and 1. */
export function usageRatio(stats: ArtworkCacheStats): number {
  if (stats.quotaBytes <= 0) return 0;
  return Math.min(1, Math.max(0, stats.bytes / stats.quotaBytes));
}

/** What the cache saved since the app started. */
export function sessionReads(stats: ArtworkCacheStats): string {
  if (stats.hits + stats.misses === 0) {
    return "Aucune image affichée depuis le lancement.";
  }
  return `Depuis le lancement : ${imageCount(stats.hits)} lue${stats.hits > 1 ? "s" : ""} sur ce poste, ${imageCount(stats.misses)} demandée${stats.misses > 1 ? "s" : ""} au serveur.`;
}

/** Announced once the cache was emptied. */
export function clearedLabel(cleared: ArtworkCacheCleared): string {
  if (cleared.entries === 0) return "Le cache était déjà vide.";
  return `Cache vidé : ${imageCount(cleared.entries)}, ${size(cleared.bytes)} libérés.`;
}
