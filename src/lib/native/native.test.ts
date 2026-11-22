import { describe, expect, it } from "vitest";

// Every source file of src/, as raw text, keyed by its path relative to this file.
const sources = import.meta.glob<string>("../../**/*.{ts,svelte}", {
  query: "?raw",
  import: "default",
  eager: true,
});

const NEEDLE = "@tauri" + "-apps";
const ALLOWED = "./tauri.ts";

describe("native seam", () => {
  it("keeps every @tauri-apps import inside lib/native/tauri.ts", () => {
    expect(Object.keys(sources)).toContain(ALLOWED);
    const offenders = Object.entries(sources)
      .filter(([file, text]) => file !== ALLOWED && text.includes(NEEDLE))
      .map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});
