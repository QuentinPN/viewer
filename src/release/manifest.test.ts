import { describe, expect, it } from "vitest";
import { androidManifest, desktopManifest } from "./manifest";

describe("desktopManifest", () => {
  it("follows the Tauri updater format", () => {
    const m = desktopManifest(
      "1.2.0",
      "- Mises à jour automatiques",
      "http://x/setup.exe",
      "c2ln\n",
      new Date("2026-11-13T18:00:00Z"),
    );
    expect(m).toEqual({
      version: "1.2.0",
      notes: "- Mises à jour automatiques",
      pub_date: "2026-11-13T18:00:00.000Z",
      platforms: {
        "windows-x86_64": { signature: "c2ln", url: "http://x/setup.exe" },
      },
    });
  });
});

describe("androidManifest", () => {
  it("carries what the TV app checks", () => {
    expect(
      androidManifest({ name: "1.1.0", code: 6 }, "notes", "http://x/a.apk", {
        bytes: 42,
        sha256: "ab",
      }),
    ).toEqual({
      versionCode: 6,
      versionName: "1.1.0",
      notes: "notes",
      url: "http://x/a.apk",
      sha256: "ab",
      size: 42,
    });
  });
});
