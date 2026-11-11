import { describe, expect, it } from "vitest";
import type { MediaItem, WatchItem } from "../emby/types";
import {
  addedMessage,
  isMenuKey,
  type MenuTarget,
  menuActions,
  PLAYLIST_NAME_MAX,
  playlistName,
  posterTarget,
  watchTarget,
} from "./actions";

const poster = (extra: Partial<MediaItem> = {}): MediaItem => ({
  id: "30207",
  name: "2 Coeurs",
  itemType: "Movie",
  year: 2024,
  runtimeMinutes: 101,
  poster: null,
  aspectRatio: null,
  played: false,
  unplayedCount: null,
  favorite: false,
  ...extra,
});

const episode = (extra: Partial<WatchItem> = {}): WatchItem => ({
  id: "70227",
  name: "Le Professeur",
  kind: "episode",
  seriesName: "La casa de papel",
  seasonIndex: 5,
  index: 2,
  image: null,
  runtimeMinutes: 50,
  resume: { positionSeconds: 600, fraction: 0.2 },
  played: false,
  favorite: false,
  ...extra,
});

const ids = (target: MenuTarget, context: Parameters<typeof menuActions>[1]) =>
  menuActions(target, context).map((a) => a.id);

describe("menuActions", () => {
  it("offers a film play, playlist, played and favourite, in that order", () => {
    const target = posterTarget(poster()) as MenuTarget;
    expect(menuActions(target, "poster").map((a) => a.label)).toEqual([
      "Lire",
      "Ajouter à une playlist",
      "Marquer comme vu",
      "Ajouter aux favoris",
    ]);
  });

  it("turns the toggles around once played or favourite", () => {
    const target = posterTarget(
      poster({ played: true, favorite: true }),
    ) as MenuTarget;
    expect(menuActions(target, "poster").map((a) => a.label)).toEqual([
      "Lire",
      "Ajouter à une playlist",
      "Marquer comme non vu",
      "Retirer des favoris",
    ]);
  });

  it("resumes an item in progress, and takes it out of Reprendre last", () => {
    const target = watchTarget(episode());
    expect(ids(target, "resume")).toEqual([
      "resume",
      "playlist",
      "played",
      "favorite",
      "removeResume",
    ]);
    expect(menuActions(target, "resume").at(-1)?.label).toBe(
      "Retirer de Reprendre",
    );
  });

  it("offers Retirer de Reprendre on that row only", () => {
    const target = watchTarget(episode({ resume: null }));
    expect(ids(target, "nextUp")).toEqual([
      "play",
      "playlist",
      "played",
      "favorite",
    ]);
    expect(ids(target, "search")).not.toContain("removeResume");
  });

  it("plays the next episode of a series, never a queue", () => {
    const target = posterTarget(
      poster({ itemType: "Series", unplayedCount: 4 }),
    ) as MenuTarget;
    const labels = menuActions(target, "poster").map((a) => a.label);
    expect(labels).toEqual([
      "Lire le prochain épisode",
      "Ajouter à une playlist",
      "Marquer comme vu",
      "Ajouter aux favoris",
    ]);
    expect(labels).not.toContain("Tout lire");
    expect(labels).not.toContain("Aléatoire");
  });
});

describe("menu targets", () => {
  it("heads a poster with its year", () => {
    expect(posterTarget(poster())).toMatchObject({
      kind: "movie",
      subtitle: "2024",
      wide: false,
      inProgress: false,
    });
    expect(posterTarget(poster({ year: null }))?.subtitle).toBeNull();
  });

  it("has no menu for a kind it does not handle", () => {
    expect(posterTarget(poster({ itemType: "BoxSet" }))).toBeNull();
  });

  it("heads an episode with its series and number", () => {
    expect(watchTarget(episode())).toMatchObject({
      kind: "episode",
      subtitle: "La casa de papel · S5 · É2",
      wide: true,
      inProgress: true,
    });
    const film = episode({
      kind: "movie",
      seriesName: null,
      seasonIndex: null,
      index: null,
      resume: null,
      played: true,
      favorite: true,
    });
    expect(watchTarget(film)).toMatchObject({
      subtitle: null,
      inProgress: false,
      played: true,
      favorite: true,
    });
  });
});

describe("isMenuKey", () => {
  it("opens on the context-menu key and Maj + F10 only", () => {
    expect(isMenuKey({ key: "ContextMenu", shiftKey: false })).toBe(true);
    expect(isMenuKey({ key: "F10", shiftKey: true })).toBe(true);
    expect(isMenuKey({ key: "F10", shiftKey: false })).toBe(false);
    expect(isMenuKey({ key: "Enter", shiftKey: true })).toBe(false);
  });
});

describe("playlistName", () => {
  it("trims the name typed", () => {
    expect(playlistName("  Soirée ciné ")).toBe("Soirée ciné");
  });

  it("refuses an empty, too long or control-character name", () => {
    expect(playlistName("   ")).toBeNull();
    expect(playlistName("a".repeat(PLAYLIST_NAME_MAX + 1))).toBeNull();
    expect(playlistName("é".repeat(PLAYLIST_NAME_MAX))).not.toBeNull();
    expect(playlistName("Liste\nde nuit")).toBeNull();
    expect(playlistName("Liste\u0007")).toBeNull();
  });
});

describe("addedMessage", () => {
  it("confirms what the server reported", () => {
    expect(addedMessage("2 Coeurs", "À revoir", 1)).toBe(
      "« 2 Coeurs » ajouté à « À revoir »",
    );
    expect(addedMessage("2 Coeurs", "À revoir", 0)).toContain(
      "n'a rien ajouté à « À revoir »",
    );
  });
});
