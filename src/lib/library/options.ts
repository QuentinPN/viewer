// Sort and filters of a library grid (ADR 0013): choices, defaults, labels and
// their per-library memory. Pure apart from the storage passed in, so it can be
// unit tested.
import type {
  ItemsOptions,
  LibraryKind,
  SortKey,
  WatchFilter,
} from "../emby/types";

export const DEFAULT_OPTIONS: Readonly<ItemsOptions> = {
  sort: "name",
  descending: false,
  watch: "all",
  favorites: false,
  genre: null,
};

export const SORTS: readonly { key: SortKey; label: string }[] = [
  { key: "name", label: "Titre" },
  { key: "dateAdded", label: "Date d'ajout" },
  { key: "year", label: "Année" },
  { key: "rating", label: "Note" },
];

/** Labels of the direction, per sort: what "ascending" means to a reader. */
export function directionLabels(sort: SortKey): {
  ascending: string;
  descending: string;
} {
  switch (sort) {
    case "name":
      return { ascending: "A → Z", descending: "Z → A" };
    case "dateAdded":
      return { ascending: "Plus anciens", descending: "Plus récents" };
    case "year":
      return { ascending: "Plus anciennes", descending: "Plus récentes" };
    case "rating":
      return { ascending: "Moins bien notés", descending: "Mieux notés" };
  }
}

/** A→Z for the title; newest or best rated first for the others. */
export const defaultDescending = (sort: SortKey): boolean => sort !== "name";

/** "En cours" is a film notion: Emby answers nothing for series. */
export function watchChoices(
  kind: LibraryKind,
): { key: WatchFilter; label: string }[] {
  const choices: { key: WatchFilter; label: string }[] = [
    { key: "all", label: "Tous" },
    { key: "unplayed", label: "Non vus" },
    { key: "played", label: "Vus" },
  ];
  if (kind === "movies") choices.push({ key: "inProgress", label: "En cours" });
  return choices;
}

/** Changing the sort also resets its direction to the natural one. */
export function withSort(options: ItemsOptions, sort: SortKey): ItemsOptions {
  return { ...options, sort, descending: defaultDescending(sort) };
}

/** Something narrows the list (the sort alone does not). */
export const isFiltered = (o: ItemsOptions): boolean =>
  o.watch !== "all" || o.favorites || o.genre !== null;

export const isDefault = (o: ItemsOptions): boolean =>
  o.sort === DEFAULT_OPTIONS.sort &&
  o.descending === DEFAULT_OPTIONS.descending &&
  !isFiltered(o);

export const sameOptions = (a: ItemsOptions, b: ItemsOptions): boolean =>
  a.sort === b.sort &&
  a.descending === b.descending &&
  a.watch === b.watch &&
  a.favorites === b.favorites &&
  a.genre === b.genre;

/** Same bound as Rust (`MAX_GENRE_CHARS`). */
const MAX_GENRE_CHARS = 100;

/** Anything read back from storage, made valid for this kind of library. */
export function sanitize(raw: unknown, kind: LibraryKind): ItemsOptions {
  const o = { ...DEFAULT_OPTIONS };
  if (typeof raw !== "object" || raw === null) return o;
  const r = raw as Record<string, unknown>;
  const sort = SORTS.find((s) => s.key === r.sort)?.key;
  if (sort) o.sort = sort;
  o.descending =
    typeof r.descending === "boolean"
      ? r.descending
      : defaultDescending(o.sort);
  const watch = watchChoices(kind).find((w) => w.key === r.watch)?.key;
  if (watch) o.watch = watch;
  o.favorites = r.favorites === true;
  if (
    typeof r.genre === "string" &&
    r.genre.trim() !== "" &&
    [...r.genre].length <= MAX_GENRE_CHARS &&
    !/[|\p{Cc}]/u.test(r.genre)
  ) {
    o.genre = r.genre;
  }
  return o;
}

/** Per server, user and library; ids only, nothing sensitive. */
export const storageKey = (
  serverId: string,
  userId: string,
  libraryId: string,
): string => `viewer.library-options.v1:${serverId}:${userId}:${libraryId}`;

/** Storage may be missing or throw (private mode, quota): defaults then. */
export function loadOptions(
  storage: Pick<Storage, "getItem"> | null,
  key: string,
  kind: LibraryKind,
): ItemsOptions {
  try {
    const text = storage?.getItem(key);
    return sanitize(text ? JSON.parse(text) : null, kind);
  } catch {
    return { ...DEFAULT_OPTIONS };
  }
}

/** Defaults are not stored: the key is removed instead. */
export function saveOptions(
  storage: Pick<Storage, "setItem" | "removeItem"> | null,
  key: string,
  options: ItemsOptions,
): void {
  try {
    if (isDefault(options)) storage?.removeItem(key);
    else storage?.setItem(key, JSON.stringify(options));
  } catch {
    // A display preference: losing it is harmless.
  }
}

/** Empty state of a filtered grid. */
export const noMatchLabel = (kind: LibraryKind): string =>
  kind === "movies"
    ? "Aucun film ne correspond à ces filtres"
    : "Aucune série ne correspond à ces filtres";
