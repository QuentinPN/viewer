// Language names for track and media labels, in French. Files carry ISO 639-1,
// 639-2/B or /T codes or BCP 47 tags (`fre`, `fra`, `fr`, `fr-FR` all seen on
// one server); Intl knows the bibliographic aliases (`fre`, `ger`, `chi`…).

const names = new Intl.DisplayNames("fr", { type: "language" });
/** "Undetermined", "no linguistic content", "multiple", "uncoded". */
const UNKNOWN = new Set(["und", "zxx", "mul", "mis"]);

/**
 * French name of the primary language (`fr-FR` → `Français`), capitalized, or
 * `null` when the code is empty, undetermined or unknown to Intl.
 */
export function languageName(code: string | null | undefined): string | null {
  const primary = code?.trim().toLowerCase().split(/[-_]/)[0] ?? "";
  if (!/^[a-z]{2,3}$/.test(primary) || UNKNOWN.has(primary)) return null;
  let name: string | undefined;
  try {
    name = names.of(primary);
  } catch {
    return null;
  }
  if (!name || name.toLowerCase() === primary) return null;
  return name.charAt(0).toLocaleUpperCase("fr") + name.slice(1);
}
