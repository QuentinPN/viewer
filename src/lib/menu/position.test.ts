import { describe, expect, it } from "vitest";
import { EDGE, placeMenu } from "./position";

const viewport = { width: 1280, height: 800 };
const menu = { width: 280, height: 300 };
const button = (left: number, top: number) => ({
  left,
  top,
  width: 32,
  height: 32,
});
const point = (left: number, top: number) => ({
  left,
  top,
  width: 0,
  height: 0,
});

describe("placeMenu", () => {
  it("opens below the anchor, left-aligned", () => {
    expect(placeMenu(button(100, 100), menu, viewport)).toEqual({
      left: 100,
      top: 138,
    });
  });

  it("flips above when there is no room below", () => {
    expect(placeMenu(button(100, 700), menu, viewport)).toEqual({
      left: 100,
      top: 700 - 6 - 300,
    });
  });

  it("aligns on the anchor's right edge near the window's right edge", () => {
    expect(placeMenu(button(1200, 100), menu, viewport)).toEqual({
      left: 1232 - 280,
      top: 138,
    });
  });

  it("stays inside the window at a pointer in a corner", () => {
    const { left, top } = placeMenu(point(1279, 799), menu, viewport);
    expect(left + menu.width).toBeLessThanOrEqual(viewport.width - EDGE);
    expect(top + menu.height).toBeLessThanOrEqual(viewport.height - EDGE);
    expect(left).toBeGreaterThanOrEqual(EDGE);
    expect(top).toBeGreaterThanOrEqual(EDGE);
  });

  it("shifts down to the edge when it fits neither below nor above", () => {
    const tall = { width: 280, height: 500 };
    const small = { width: 1280, height: 600 };
    expect(placeMenu(button(100, 250), tall, small).top).toBe(600 - EDGE - 500);
  });

  it("starts at the top edge when taller than the window", () => {
    const tall = { width: 280, height: 900 };
    expect(placeMenu(button(100, 100), tall, viewport).top).toBe(EDGE);
  });

  it("stays under the title bar", () => {
    const tall = { width: 280, height: 900 };
    expect(placeMenu(button(100, 100), tall, viewport, 40).top).toBe(40);
    // Not above the anchor either when that would go under it.
    const short = { width: 1280, height: 400 };
    expect(placeMenu(button(100, 200), menu, short, 40).top).toBe(
      400 - EDGE - 300,
    );
  });

  it("starts at the left edge when wider than the window", () => {
    const narrow = { width: 200, height: 800 };
    expect(placeMenu(point(50, 50), menu, narrow).left).toBe(EDGE);
  });
});
