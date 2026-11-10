import { describe, expect, it } from "vitest";
import type { Segment } from "../emby/types";
import {
  activeSegment,
  DEFAULT_SKIP,
  NO_SKIP_SHOWN,
  offersNextToo,
  readSkip,
  SKIP_KEY,
  SKIP_SHOWN_MS,
  saveSkip,
  segmentEnd,
  skipAction,
  skipHidesIn,
  skipShown,
  trackSkipShown,
} from "./segments";

describe("skip button hiding (ADR 0089)", () => {
  it("shows for ten seconds once its segment starts, then hides", () => {
    const shown = trackSkipShown(NO_SKIP_SHOWN, "intro@50", 1_000);
    expect(skipShown(shown, 1_000, false)).toBe(true);
    expect(skipShown(shown, 1_000 + SKIP_SHOWN_MS - 1, false)).toBe(true);
    expect(skipShown(shown, 1_000 + SKIP_SHOWN_MS, false)).toBe(false);
    expect(skipHidesIn(shown, 4_000)).toBe(SKIP_SHOWN_MS - 3_000);
    expect(skipHidesIn(shown, 1_000 + SKIP_SHOWN_MS)).toBeNull();
  });

  it("comes back with the controls once hidden", () => {
    const shown = trackSkipShown(NO_SKIP_SHOWN, "intro@50", 0);
    expect(skipShown(shown, 30_000, true)).toBe(true);
    expect(skipShown(shown, 30_001, false)).toBe(false);
  });

  it("keeps its countdown within the same segment", () => {
    const first = trackSkipShown(NO_SKIP_SHOWN, "intro@50", 0);
    const later = trackSkipShown(first, "intro@50", 20_000);
    expect(later).toBe(first);
    expect(skipShown(later, 20_000, false)).toBe(false);
  });

  it("restarts for the next segment, or after leaving this one", () => {
    const intro = trackSkipShown(NO_SKIP_SHOWN, "intro@50", 0);
    const credits = trackSkipShown(intro, "credits@1345", 60_000);
    expect(skipShown(credits, 60_000, false)).toBe(true);
    // A seek before the intro's start, then playback back into it
    const out = trackSkipShown(intro, null, 20_000);
    expect(skipShown(out, 20_000, true)).toBe(false);
    expect(skipHidesIn(out, 20_000)).toBeNull();
    const again = trackSkipShown(out, "intro@50", 25_000);
    expect(skipShown(again, 25_000, false)).toBe(true);
    expect(skipHidesIn(again, 25_000)).toBe(SKIP_SHOWN_MS);
  });
});

function memoryStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map(Object.entries(initial));
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (k) => data.get(k) ?? null,
    key: (i) => [...data.keys()][i] ?? null,
    removeItem: (k) => void data.delete(k),
    setItem: (k, v) => void data.set(k, v),
  };
}

const intro: Segment = {
  kind: "intro",
  start: 0,
  end: 24.7,
  source: "theIntroDb",
};
const credits: Segment = {
  kind: "credits",
  start: 1332.1,
  end: 1441.1,
  source: "theIntroDb",
};
const openCredits: Segment = {
  kind: "credits",
  start: 2400,
  end: null,
  source: "emby",
};
// Credits followed by a scene: TheIntroDB's film example.
const midCredits: Segment = {
  kind: "credits",
  start: 5801.8,
  end: 6371.1,
  source: "theIntroDb",
};

describe("skip setting", () => {
  it("asks the external sources and does not skip on its own by default", () => {
    expect(readSkip(null)).toEqual(DEFAULT_SKIP);
    expect(DEFAULT_SKIP).toEqual({ external: true, auto: false });
  });

  it("keeps what was saved and ignores malformed values", () => {
    const storage = memoryStorage();
    saveSkip(storage, { external: false, auto: true });
    expect(readSkip(storage)).toEqual({ external: false, auto: true });
    expect(readSkip(memoryStorage({ [SKIP_KEY]: "{" }))).toEqual(DEFAULT_SKIP);
    expect(readSkip(memoryStorage({ [SKIP_KEY]: "null" }))).toEqual(
      DEFAULT_SKIP,
    );
    expect(
      readSkip(memoryStorage({ [SKIP_KEY]: '{"external":"no","auto":true}' })),
    ).toEqual({ external: true, auto: true });
  });

  it("survives a storage that refuses writes", () => {
    const refusing = {
      ...memoryStorage(),
      setItem: () => {
        throw new Error("quota");
      },
    };
    expect(() => saveSkip(refusing, DEFAULT_SKIP)).not.toThrow();
  });
});

describe("active segment", () => {
  const all = [intro, credits];

  it("shows the button inside a segment only", () => {
    expect(activeSegment(all, 0, 1441.1)).toBe(intro);
    expect(activeSegment(all, 10, 1441.1)).toBe(intro);
    expect(activeSegment(all, 100, 1441.1)).toBeNull();
    expect(activeSegment(all, 1400, 1441.1)).toBe(credits);
  });

  it("hides it in the last half second", () => {
    expect(activeSegment(all, 24.3, 1441.1)).toBeNull();
    expect(activeSegment(all, 24.1, 1441.1)).toBe(intro);
  });

  it("waits for the duration for credits with no end", () => {
    expect(activeSegment([openCredits], 2500, 0)).toBeNull();
    expect(activeSegment([openCredits], 2500, 2700)).toBe(openCredits);
  });
});

describe("skip action", () => {
  it("jumps to the end of the intro", () => {
    expect(skipAction(intro, 1441.1, true)).toEqual({
      kind: "seek",
      to: 24.7,
      label: "Passer l'intro",
    });
  });

  it("offers the next episode when the credits end the file", () => {
    expect(skipAction(credits, 1441.1, true)).toEqual({
      kind: "next",
      label: "Épisode suivant",
    });
    // Within two seconds of the end counts as the end.
    expect(skipAction(credits, 1442.5, true)?.kind).toBe("next");
    expect(skipAction(openCredits, 2700, true)?.kind).toBe("next");
  });

  it("skips to the end without a next episode", () => {
    expect(skipAction(credits, 1441.1, false)).toEqual({
      kind: "seek",
      to: 1441.1,
      label: "Passer le générique",
    });
    expect(segmentEnd(openCredits, 2700)).toBe(2700);
  });

  it("skips credits followed by a scene, and offers the next episode too", () => {
    expect(skipAction(midCredits, 6408, true)).toEqual({
      kind: "seek",
      to: 6371.1,
      label: "Passer le générique",
    });
    expect(offersNextToo(midCredits, 6408, true)).toBe(true);
    expect(offersNextToo(midCredits, 6408, false)).toBe(false);
    expect(offersNextToo(credits, 1441.1, true)).toBe(false);
    expect(offersNextToo(intro, 1441.1, true)).toBe(false);
  });
});
