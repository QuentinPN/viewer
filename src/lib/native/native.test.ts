import { describe, expect, it } from "vitest";

// Every source file of src/, as raw text, keyed by its path relative to this file.
const sources = import.meta.glob<string>("../../**/*.{ts,svelte}", {
  query: "?raw",
  import: "default",
  eager: true,
});

const NEEDLE = "/wails/" + "runtime";
const ALLOWED = "./wails.ts";

describe("native seam", () => {
  it("keeps the Wails runtime inside lib/native/wails.ts", () => {
    expect(sources[ALLOWED]).toContain(NEEDLE);
    const offenders = Object.entries(sources)
      .filter(([file, text]) => file !== ALLOWED && text.includes(NEEDLE))
      .map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
