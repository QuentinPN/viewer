import { describe, expect, it } from "vitest";
import { formatTime, trackLabel } from "./format";
import type { Track } from "./types";

const track = (over: Partial<Track>): Track => ({
  id: 1,
  kind: "audio",
  title: null,
  lang: null,
  codec: null,
  selected: false,
  external: false,
  default: false,
  ...over,
});

describe("formatTime", () => {
  it("formats minutes and hours", () => {
    expect(formatTime(0)).toBe("0:00");
    expect(formatTime(65.9)).toBe("1:05");
    expect(formatTime(3600 + 62)).toBe("1:01:02");
  });

  it("handles invalid input", () => {
    expect(formatTime(null)).toBe("0:00");
    expect(formatTime(Number.NaN)).toBe("0:00");
    expect(formatTime(-3)).toBe("0:00");
  });
});

describe("trackLabel", () => {
  it("translates known languages and joins parts", () => {
    expect(
      trackLabel(track({ lang: "fre", codec: "ass", title: "Complets" })),
    ).toBe("Complets · Français · ASS");
  });

  it("falls back to the track number and marks external tracks", () => {
    expect(trackLabel(track({ id: 3 }))).toBe("Piste 3");
    expect(trackLabel(track({ lang: "xyz", external: true }))).toBe(
      "xyz (externe)",
    );
  });
});
