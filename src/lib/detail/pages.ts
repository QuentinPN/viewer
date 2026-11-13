// Pages opened from a detail page over it (ADR 0121): another title, or a list
// of titles (a person, a collection, a genre, a studio). Back pops the top one.
// Pure, so it can be unit tested.
import type {
  CastMember,
  ImageRef,
  MediaItem,
  TitlesSource,
} from "../emby/types";

/** Pages kept over the first detail: older ones drop off, as on the TV. */
export const MAX_PAGES = 10;

export type DetailPage =
  | { kind: "item"; item: MediaItem; via: "previous" | "next" | null }
  | {
      kind: "titles";
      source: TitlesSource;
      title: string;
      /** Under the title: what the list holds. */
      subtitle: string;
      photo: ImageRef | null;
    };

/** `page` over `pages`, the oldest dropped beyond `MAX_PAGES`. */
export function pushPage(pages: DetailPage[], page: DetailPage): DetailPage[] {
  return [...pages, page].slice(-MAX_PAGES);
}

/** The top page swapped for `page`, as a neighbour episode replaces its own. */
export function replaceTop(
  pages: DetailPage[],
  page: DetailPage,
): DetailPage[] {
  return pages.length === 0 ? [page] : [...pages.slice(0, -1), page];
}

/** Stable key of a page at its depth: a new page remounts, scroll starts at 0. */
export function pageKey(page: DetailPage, depth: number): string {
  const id = page.kind === "item" ? page.item.id : sourceKey(page.source);
  return `${depth}:${page.kind}:${id}`;
}

function sourceKey(source: TitlesSource): string {
  return `${source.kind}:${source.id}`;
}

/** The page of a cast member; null when the server gave no usable id. */
export function personPage(person: CastMember): DetailPage | null {
  if (!person.id) return null;
  return {
    kind: "titles",
    source: { kind: "person", id: person.id },
    title: person.name,
    subtitle: "Ses films et séries dans ta bibliothèque",
    photo: person.photo,
  };
}

/** Up to two initials, for a person or title without an image. */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter((w) => /\p{L}|\p{N}/u.test(w))
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? "")
    .join("");
}

/** "3 titres", "1 titre", "Aucun titre". */
export function titlesCount(n: number): string {
  if (n === 0) return "Aucun titre";
  return `${n} titre${n > 1 ? "s" : ""}`;
}
