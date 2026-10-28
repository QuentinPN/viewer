import { describe, expect, it } from "vitest";
import type { WatchItem } from "../emby/types";
import {
  episodeCode,
  remainingLabel,
  watchAction,
  watchMeta,
  watchTitle,
  withoutDuplicates,
} from "./format";

const episode: WatchItem = {
  id: "70227",
  name: "Le Professeur",
  kind: "episode",
  seriesName: "La casa de papel",
  seasonIndex: 5,
  index: 2,
  image: null,
  runtimeMinutes: 50,
  resume: { positionSeconds: 600, fraction: 0.2 },
};
const film: WatchItem = {
  id: "30207",
  name: "2 Coeurs",
  kind: "movie",
  seriesName: null,
  seasonIndex: null,
  index: null,
  image: null,
  runtimeMinutes: 101,
  resume: null,
};

describe("home card labels", () => {
  it("titles an episode by its series and numbers it", () => {
    expect(watchTitle(episode)).toBe("La casa de papel");
    expect(episodeCode(episode)).toBe("S5 · É2");
    expect(episodeCode({ ...episode, seasonIndex: 0, index: 1 })).toBe(
      "Spécial 1",
    );
    expect(episodeCode({ ...episode, seasonIndex: null, index: null })).toBe(
      null,
    );
    expect(episodeCode(film)).toBe(null);
  });

  it("shows the remaining time of a started item, else the runtime", () => {
    expect(remainingLabel(episode)).toBe("Reste 40 min");
    expect(watchMeta(episode)).toBe("S5 · É2 · Le Professeur · Reste 40 min");
    expect(watchMeta(film)).toBe("1 h 41");
    expect(
      watchMeta({ ...film, resume: { positionSeconds: 1831, fraction: 0.3 } }),
    ).toBe("Reste 1 h 10");
    expect(remainingLabel({ ...episode, runtimeMinutes: null })).toBe(null);
  });

  it("says that the card plays or resumes", () => {
    expect(watchAction(episode)).toBe(
      "Reprendre La casa de papel, saison 5 épisode 2, Le Professeur",
    );
    expect(watchAction(film)).toBe("Lire 2 Coeurs");
  });

  it("drops next-up episodes already in progress", () => {
    const next = { ...episode, id: "70229", resume: null };
    expect(withoutDuplicates([episode, next], [episode, film])).toEqual([next]);
  });
});
