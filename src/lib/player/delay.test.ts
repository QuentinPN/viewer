import { describe, expect, it } from "vitest";
import { changeDelay, formatDelay } from "./delay";

describe("changeDelay", () => {
  it("steps by a tenth without drifting", () => {
    let d = 0;
    for (let i = 0; i < 3; i++) d = changeDelay(d, "later");
    expect(d).toBe(0.3);
    expect(changeDelay(0.3, "earlier")).toBe(0.2);
    expect(changeDelay(-0.1, "earlier")).toBe(-0.2);
  });
  it("holds within ten seconds", () => {
    expect(changeDelay(10, "later")).toBe(10);
    expect(changeDelay(-10, "earlier")).toBe(-10);
  });
  it("resets to none", () => {
    expect(changeDelay(2.5, "reset")).toBe(0);
  });
  it("treats a bad value as none", () => {
    expect(changeDelay(Number.NaN, "later")).toBe(0.1);
  });
});

describe("formatDelay", () => {
  it("reads in French with a real minus sign", () => {
    expect(formatDelay(0)).toBe("Aucun");
    expect(formatDelay(0.3)).toBe("+0,3 s");
    expect(formatDelay(-1.2)).toBe("−1,2 s");
    expect(formatDelay(0.04)).toBe("Aucun");
  });
});
