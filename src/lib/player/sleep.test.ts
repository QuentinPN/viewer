import { describe, expect, it } from "vitest";
import type { Segment } from "../emby/types";
import {
  holdsNext,
  SLEEP_OFF,
  sameChoice,
  sleepBadge,
  sleepChoices,
  sleepDue,
  sleepMinutesLeft,
  sleepStop,
  sleepTimer,
  sleepValue,
} from "./sleep";

const credits = (start: number): Segment => ({
  kind: "credits",
  start,
  end: null,
  source: "emby",
});

describe("sleep timer", () => {
  it("offers off, minutes, then the end of the item", () => {
    const choices = sleepChoices();
    expect(choices[0]).toEqual({ kind: "off" });
    expect(choices.at(-1)).toEqual({ kind: "endOfItem" });
    expect(choices.map((c) => sleepValue(c, "episode"))).toEqual([
      "Désactivée",
      "15 min",
      "30 min",
      "45 min",
      "60 min",
      "90 min",
      "Fin de l'épisode",
    ]);
    expect(sleepValue({ kind: "endOfItem" }, "movie")).toBe("Fin du film");
  });

  it("runs minutes out on the wall clock", () => {
    const t = sleepTimer({ kind: "minutes", minutes: 15 }, 1000, 50);
    expect(sleepDue(t, 1000 + 14 * 60_000, 0, 100, [])).toBe(false);
    expect(sleepDue(t, 1000 + 15 * 60_000, 0, 100, [])).toBe(true);
    expect(sleepMinutesLeft(t, 1000 + 60_001)).toBe(14);
    expect(sleepBadge(t, 1000, "movie")).toBe("Arrêt dans 15 min");
    expect(holdsNext(t)).toBe(false);
  });

  it("stops the episode at its credits, else at its end", () => {
    expect(sleepStop([credits(1200)], 1300, 0)).toBe(1200);
    // Credits already started when it was set do not count.
    expect(sleepStop([credits(1200)], 1300, 1250)).toBe(1299);
    expect(sleepStop([], 0, 0)).toBeNull();
    const t = sleepTimer({ kind: "endOfItem" }, 0, 10);
    expect(holdsNext(t)).toBe(true);
    expect(sleepDue(t, 0, 1199, 1300, [credits(1200)])).toBe(false);
    expect(sleepDue(t, 0, 1200, 1300, [credits(1200)])).toBe(true);
    expect(sleepBadge(t, 0, "episode")).toBe("Arrêt : fin de l'épisode");
  });

  it("is off by default", () => {
    expect(sleepDue(SLEEP_OFF, 1e15, 1e9, 1, [])).toBe(false);
    expect(sleepBadge(SLEEP_OFF, 0, "movie")).toBeNull();
    expect(
      sameChoice(
        { kind: "minutes", minutes: 15 },
        { kind: "minutes", minutes: 30 },
      ),
    ).toBe(false);
  });
});
