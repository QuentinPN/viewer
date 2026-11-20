import { describe, expect, it } from "vitest";
import androidGradle from "../../android-tv/app/build.gradle.kts?raw";
import pkg from "../../package.json";
import lock from "../../package-lock.json";
import cargoLock from "../../src-tauri/Cargo.lock?raw";
import cargoToml from "../../src-tauri/Cargo.toml?raw";
import tauriConf from "../../src-tauri/tauri.conf.json";
import {
  androidReleaseNotes,
  cargoLockVersion,
  cargoTomlVersion,
  changelogSection,
  gradleVersion,
  releaseNotes,
} from "./version";

describe("the version of the repository", () => {
  it("is the same in npm and Cargo files", () => {
    expect(cargoTomlVersion(cargoToml)).toBe(pkg.version);
    expect(cargoLockVersion(cargoLock, "viewer")).toBe(pkg.version);
    expect(lock.version).toBe(pkg.version);
    expect(lock.packages[""].version).toBe(pkg.version);
  });

  it("is read by Tauri from package.json", () => {
    expect(tauriConf.version).toBe("../package.json");
  });
});

describe("release notes", () => {
  const changelog =
    "# Journal des versions\r\n\r\n## 1.0.1 — 2026-11-08\r\n\r\n- Fix A\r\n- Fix B\r\n\r\n## 1.0.0 — 2026-11-07\r\n\r\n- Old\r\n";

  it("takes the section of one version only", () => {
    expect(changelogSection(changelog, "1.0.1")).toBe("- Fix A\n- Fix B");
    expect(changelogSection(changelog, "1.0.0")).toBe("- Old");
    expect(changelogSection(changelog, "1.0")).toBeNull();
    expect(changelogSection(changelog, "2.0.0")).toBeNull();
  });

  it("ends with the installer name, size and hash", () => {
    const notes = releaseNotes("1.0.1", "- Fix A", {
      name: "Viewer_1.0.1_x64-setup.exe",
      bytes: 37450769,
      sha256: "B129DD",
    });
    expect(notes.startsWith("## Viewer 1.0.1\n\n- Fix A\n")).toBe(true);
    expect(notes).toContain(
      "`Viewer_1.0.1_x64-setup.exe` — 35,72 Mio — SHA-256 `b129dd`",
    );
  });
});

describe("gradleVersion", () => {
  it("reads the Android TV app's version", () => {
    expect(gradleVersion(androidGradle)).toBeDefined();
    expect(
      gradleVersion('    versionCode = 3\n    versionName = "1.0.1"\n'),
    ).toEqual({ name: "1.0.1", code: 3 });
  });

  it("ignores the debug suffix and a missing field", () => {
    expect(
      gradleVersion(
        '        versionCode = 3\n        versionName = "1.0.1"\n            versionNameSuffix = "-debug"\n',
      )?.name,
    ).toBe("1.0.1");
    expect(gradleVersion('versionName = "1.0.1"')).toBeUndefined();
  });
});

describe("androidReleaseNotes", () => {
  it("puts the changelog section, then the APK", () => {
    const notes = androidReleaseNotes({ name: "1.0.1", code: 3 }, "- Fix A", {
      name: "Viewer-TV_1.0.1.apk",
      bytes: 37450769,
      sha256: "B129DD",
    });
    expect(
      notes.startsWith("## Viewer Android TV 1.0.1\n\n- Fix A\n"),
    ).toBe(true);
    expect(notes).toContain("code de version 3");
    expect(notes).toContain(
      "`Viewer-TV_1.0.1.apk` — 35,72 Mio — SHA-256 `b129dd`",
    );
  });
});
