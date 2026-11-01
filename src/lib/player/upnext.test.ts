import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { playbackHeading } from "./format";
import {
  isEpisode,
  nextTitle,
  startCountdown,
  UP_NEXT_SECONDS,
} from "./upnext";

const episode = {
  name: "La Nouvelle Recrue",
  seriesName: "2.5 Dimensional Seduction",
  seasonIndex: 1,
  index: 1,
  indexEnd: null,
};

describe("nextTitle", () => {
  it("keeps the series and takes the next episode's place and name", () => {
    const title = nextTitle(
      {
        id: "2",
        name: "Le Club",
        seasonIndex: 2,
        index: 1,
        indexEnd: null,
      },
      episode,
    );
    expect(playbackHeading(title)).toEqual({
      kicker: "2.5 Dimensional Seduction · S2 · É1",
      name: "Le Club",
    });
  });
});

describe("isEpisode", () => {
  it("is true only with a series", () => {
    expect(isEpisode(episode)).toBe(true);
    expect(isEpisode({ ...episode, seriesName: null })).toBe(false);
    expect(isEpisode(null)).toBe(false);
  });
});

describe("startCountdown", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("ticks every second and ends once at 0", () => {
    const ticks: number[] = [];
    const done = vi.fn();
    startCountdown(UP_NEXT_SECONDS, (left) => ticks.push(left), done);
    vi.advanceTimersByTime(9000);
    expect(ticks).toEqual([9, 8, 7, 6, 5, 4, 3, 2, 1]);
    expect(done).not.toHaveBeenCalled();
    vi.advanceTimersByTime(5000);
    expect(ticks.at(-1)).toBe(0);
    expect(done).toHaveBeenCalledTimes(1);
  });

  it("stops for good when cancelled", () => {
    const done = vi.fn();
    const stop = startCountdown(3, () => {}, done);
    vi.advanceTimersByTime(1000);
    stop();
    stop();
    vi.advanceTimersByTime(5000);
    expect(done).not.toHaveBeenCalled();
  });
});
