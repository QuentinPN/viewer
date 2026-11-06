import { describe, expect, it } from "vitest";
import { changeFavorite } from "./favorite";

describe("changeFavorite", () => {
  it("shows the wanted state at once, then the server's answer", async () => {
    const shown: boolean[] = [];
    const sent: boolean[] = [];
    const result = await changeFavorite(
      true,
      (f) => shown.push(f),
      async (f) => {
        sent.push(f);
        expect(shown).toEqual([true]);
        return true;
      },
    );
    expect(result).toBe(true);
    expect(sent).toEqual([true]);
    expect(shown).toEqual([true, true]);
  });

  it("follows the server when it reports another state", async () => {
    const shown: boolean[] = [];
    const result = await changeFavorite(
      false,
      (f) => shown.push(f),
      async () => true,
    );
    expect(result).toBe(true);
    expect(shown).toEqual([false, true]);
  });

  it("puts the previous state back and rethrows on failure", async () => {
    const shown: boolean[] = [];
    const error = { kind: "network", message: "Serveur injoignable" };
    await expect(
      changeFavorite(
        true,
        (f) => shown.push(f),
        async () => {
          throw error;
        },
      ),
    ).rejects.toBe(error);
    expect(shown).toEqual([true, false]);
  });
});
