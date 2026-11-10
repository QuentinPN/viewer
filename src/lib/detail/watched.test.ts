import { describe, expect, it } from "vitest";
import type { PlaybackStopped } from "../emby/types";
import {
  changePlayed,
  patchPlayed,
  playedAction,
  restoreItem,
  withoutItem,
} from "./watched";

const state = (played: boolean): PlaybackStopped => ({
  itemId: "30207",
  played,
  resume: null,
});

describe("changePlayed", () => {
  it("shows the wanted state at once, then the server's answer", async () => {
    const shown: boolean[] = [];
    const result = await changePlayed(
      true,
      (p) => shown.push(p),
      async (p) => {
        expect(shown).toEqual([true]);
        return state(p);
      },
    );
    expect(result).toEqual(state(true));
    expect(shown).toEqual([true, true]);
  });

  it("follows the server when it reports another state", async () => {
    const shown: boolean[] = [];
    await changePlayed(
      true,
      (p) => shown.push(p),
      async () => state(false),
    );
    expect(shown).toEqual([true, false]);
  });

  it("puts the previous state back and rethrows on failure", async () => {
    const shown: boolean[] = [];
    const failure = { kind: "unreachable", message: "hors ligne" };
    await expect(
      changePlayed(
        false,
        (p) => shown.push(p),
        async () => {
          throw failure;
        },
      ),
    ).rejects.toBe(failure);
    expect(shown).toEqual([false, true]);
  });
});

describe("playedAction", () => {
  it("names what the toggle does", () => {
    expect(playedAction(false)).toBe("Marquer comme vu");
    expect(playedAction(true)).toBe("Marquer comme non vu");
    expect(playedAction(false, "Pilote")).toBe("Marquer « Pilote » comme vu");
    expect(playedAction(true, "Pilote")).toBe(
      "Marquer « Pilote » comme non vu",
    );
  });
});

describe("patchPlayed", () => {
  it("patches the matching cards only", () => {
    const items = [
      { id: "1", played: false, unplayedCount: null },
      { id: "30207", played: false, unplayedCount: 4 },
    ];
    expect(patchPlayed(items, state(true))).toBe(true);
    expect(items).toEqual([
      { id: "1", played: false, unplayedCount: null },
      { id: "30207", played: true, unplayedCount: null },
    ]);
  });

  it("leaves a card that already shows the state alone", () => {
    const items = [{ id: "30207", played: true, unplayedCount: 3 }];
    expect(patchPlayed(items, state(true))).toBe(true);
    expect(items[0]?.unplayedCount).toBe(3);
  });

  it("reports an item it does not show", () => {
    const items = [{ id: "1", played: false }];
    expect(patchPlayed(items, state(true))).toBe(false);
    expect(items).toEqual([{ id: "1", played: false }]);
  });
});

describe("withoutItem and restoreItem", () => {
  const a = { id: "a" };
  const b = { id: "b" };
  const c = { id: "c" };

  it("removes an item and puts it back at its place", () => {
    const { items, removed } = withoutItem([a, b, c], "b");
    expect(items).toEqual([a, c]);
    expect(removed).toEqual({ item: b, index: 1 });
    expect(
      restoreItem(items, removed as { item: typeof b; index: number }),
    ).toEqual([a, b, c]);
  });

  it("does nothing for an item not listed", () => {
    const list = [a, c];
    expect(withoutItem(list, "b")).toEqual({ items: list, removed: null });
  });

  it("puts it at the end when the list got shorter, never twice", () => {
    expect(restoreItem([a], { item: c, index: 2 })).toEqual([a, c]);
    expect(restoreItem([a, c], { item: c, index: 0 })).toEqual([a, c]);
  });
});
