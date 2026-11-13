import { describe, expect, it } from "vitest";
import { mapPlayerKey } from "./keyboard";

describe("mapPlayerKey", () => {
  it("moves between chapters with Page Up and Page Down", () => {
    expect(mapPlayerKey("PageUp", { inTextField: false })).toEqual({
      kind: "chapter",
      delta: -1,
    });
    expect(mapPlayerKey("PageDown", { inTextField: false })).toEqual({
      kind: "chapter",
      delta: 1,
    });
    expect(mapPlayerKey("PageDown", { inTextField: true })).toBeNull();
  });

  it("toggles pause with Space and k", () => {
    const expected = { kind: "command", command: { type: "togglePause" } };
    expect(mapPlayerKey(" ", { inTextField: false })).toEqual(expected);
    expect(mapPlayerKey("k", { inTextField: false })).toEqual(expected);
  });

  it("seeks 10 s, or 30 s with Shift", () => {
    expect(mapPlayerKey("ArrowLeft", { inTextField: false })).toEqual({
      kind: "command",
      command: { type: "seekRelative", seconds: -10 },
    });
    expect(
      mapPlayerKey("ArrowRight", { inTextField: false, shift: true }),
    ).toEqual({
      kind: "command",
      command: { type: "seekRelative", seconds: 30 },
    });
  });

  it("handles volume, mute and info", () => {
    expect(mapPlayerKey("ArrowUp", { inTextField: false })).toEqual({
      kind: "volume",
      delta: 5,
    });
    expect(mapPlayerKey("ArrowDown", { inTextField: false })).toEqual({
      kind: "volume",
      delta: -5,
    });
    expect(mapPlayerKey("m", { inTextField: false })).toEqual({
      kind: "toggleMute",
    });
    expect(mapPlayerKey("M", { inTextField: false })).toEqual({
      kind: "toggleMute",
    });
    expect(mapPlayerKey("i", { inTextField: false })).toEqual({
      kind: "toggleInfo",
    });
    expect(mapPlayerKey("F", { inTextField: false })).toEqual({
      kind: "toggleFullscreen",
    });
  });

  it("never hijacks typing in text fields", () => {
    expect(mapPlayerKey(" ", { inTextField: true })).toBeNull();
    expect(mapPlayerKey("f", { inTextField: true })).toBeNull();
  });

  it("moves the subtitles earlier and later with Z and X, as in mpv", () => {
    expect(mapPlayerKey("z", { inTextField: false })).toEqual({
      kind: "subtitleDelay",
      change: "earlier",
    });
    expect(mapPlayerKey("X", { inTextField: false })).toEqual({
      kind: "subtitleDelay",
      change: "later",
    });
  });

  it("ignores unrelated keys", () => {
    expect(mapPlayerKey("a", { inTextField: false })).toBeNull();
  });
});
